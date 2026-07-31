package swp391.carwash.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.MeResponse;
import swp391.carwash.dto.request.Staff.CreateStaffRequest;
import swp391.carwash.dto.request.Staff.UpdateStaffAssignmentRequest;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.Role;
import swp391.carwash.entity.UserRole;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.GarageStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.RoleName;
import swp391.carwash.enums.UserStatus;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.repository.RoleRepository;
import swp391.carwash.repository.UserRoleRepository;

/**
 * Quản lý nhân sự vận hành (STAFF/MANAGER) theo chi nhánh.
 *
 * <p>Trước đây hệ thống chỉ tạo được Garage mà không có cách nào tạo/gán nhân viên cho nó,
 * nên chi nhánh mới luôn rỗng: booking không gán được người phụ trách (trigger
 * validate_assigned_staff_role từ chối), và JWT claim garageIds rỗng khiến
 * {@link swp391.carwash.security.GarageAccessEvaluator} chặn mọi thao tác của staff.
 *
 * <p>Quan hệ nhân viên–chi nhánh nằm ở bảng user_role(user_id, role_id, garage_id),
 * nên 1 nhân viên có thể phụ trách nhiều chi nhánh và 1 chi nhánh có nhiều nhân viên.
 */
@Service
@RequiredArgsConstructor
public class AdminStaffService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminStaffService.class);

    /** Chỉ những vai trò này mới gắn được với garage cụ thể. */
    private static final Set<RoleName> OPERATIONAL_ROLES = EnumSet.of(RoleName.STAFF, RoleName.MANAGER);

    /** Booking chưa kết thúc — còn cần người phụ trách. */
    private static final Set<BookingStatus> ACTIVE_BOOKING_STATUSES = EnumSet.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN,
            BookingStatus.WASHING);

    private final AppUserRepository appUserRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final GarageRepository garageRepository;
    private final BookingRepository bookingRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public MeResponse createStaff(CreateStaffRequest request) {
        RoleName roleName = parseOperationalRole(request.role());
        List<Garage> garages = resolveGarages(request.garageIds());

        String email = request.email().trim().toLowerCase();
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }
        if (appUserRepository.existsByPhone(request.phone())) {
            throw new ApiException(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng");
        }

        // Admin tạo trực tiếp -> ACTIVE luôn, không qua luồng OTP như khách tự đăng ký.
        AppUser user = AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .status(UserStatus.ACTIVE)
                .build();
        appUserRepository.save(user);

        Role role = resolveRole(roleName);
        for (Garage garage : garages) {
            UserRole assignment = userRoleRepository.save(UserRole.builder()
                    .user(user)
                    .role(role)
                    .garage(garage)
                    .status(RecordStatus.ACTIVE)
                    .build());
            // Đồng bộ collection in-memory để MeResponse.from() đọc được ngay trong cùng transaction.
            user.getUserRoles().add(assignment);
        }

        log.info("Admin created operational account: userId={}, role={}, garageIds={}",
                user.getId(), roleName, request.garageIds());
        return MeResponse.from(user);
    }

    @Transactional
    public MeResponse updateAssignment(Integer userId, UpdateStaffAssignmentRequest request) {
        RoleName roleName = parseOperationalRole(request.role());
        List<Garage> garages = resolveGarages(request.garageIds());

        AppUser user = appUserRepository.findWithUserRolesById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(HttpStatus.CONFLICT, "Tài khoản đã bị xoá");
        }

        List<UserRole> currentAssignments =
                userRoleRepository.findByUserIdAndRoleRoleNameIn(userId, OPERATIONAL_ROLES);

        Set<Integer> keptGarageIds = new LinkedHashSet<>(request.garageIds());
        List<Integer> removedGarageIds = currentAssignments.stream()
                .map(UserRole::getGarage)
                .filter(java.util.Objects::nonNull)
                .map(Garage::getId)
                .filter(garageId -> !keptGarageIds.contains(garageId))
                .distinct()
                .toList();

        // Không cho gỡ chi nhánh khi nhân viên còn booking dở dang ở đó, tránh booking mồ côi.
        if (!removedGarageIds.isEmpty()) {
            long blocking = bookingRepository.countActiveAssignmentsInGarages(
                    userId, removedGarageIds, ACTIVE_BOOKING_STATUSES);
            if (blocking > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Nhân viên còn %d booking chưa hoàn tất tại chi nhánh bị gỡ. Hãy bàn giao trước."
                                .formatted(blocking));
            }
        }

        // Thay thế toàn bộ phân công vận hành; các role khác (vd CUSTOMER) giữ nguyên.
        userRoleRepository.deleteAll(currentAssignments);
        user.getUserRoles().removeAll(currentAssignments);
        userRoleRepository.flush();

        Role role = resolveRole(roleName);
        for (Garage garage : garages) {
            UserRole assignment = userRoleRepository.save(UserRole.builder()
                    .user(user)
                    .role(role)
                    .garage(garage)
                    .status(RecordStatus.ACTIVE)
                    .build());
            user.getUserRoles().add(assignment);
        }

        log.info("Admin updated operational assignment: userId={}, role={}, garageIds={}",
                userId, roleName, request.garageIds());
        return MeResponse.from(user);
    }

    private RoleName parseOperationalRole(String rawRole) {
        RoleName roleName;
        try {
            roleName = RoleName.valueOf(rawRole.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ: " + rawRole);
        }
        if (!OPERATIONAL_ROLES.contains(roleName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Chỉ hỗ trợ vai trò STAFF hoặc MANAGER cho nhân sự chi nhánh");
        }
        return roleName;
    }

    private List<Garage> resolveGarages(List<Integer> garageIds) {
        List<Garage> garages = new ArrayList<>();
        for (Integer garageId : new LinkedHashSet<>(garageIds)) {
            Garage garage = garageRepository.findById(garageId)
                    .filter(candidate -> candidate.getStatus() != GarageStatus.DELETED)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.BAD_REQUEST, "Không tìm thấy chi nhánh với ID: " + garageId));
            garages.add(garage);
        }
        return garages;
    }

    private Role resolveRole(RoleName roleName) {
        return roleRepository.findByRoleName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName(roleName)
                        .description(roleName.name() + " role")
                        .status(RecordStatus.ACTIVE)
                        .build()));
    }
}
