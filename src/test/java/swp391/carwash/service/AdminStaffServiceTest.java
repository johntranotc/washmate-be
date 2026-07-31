package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.MeResponse;
import swp391.carwash.dto.request.Staff.CreateStaffRequest;
import swp391.carwash.dto.request.Staff.UpdateStaffAssignmentRequest;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.Role;
import swp391.carwash.entity.UserRole;
import swp391.carwash.enums.GarageStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.RoleName;
import swp391.carwash.enums.UserStatus;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.repository.RoleRepository;
import swp391.carwash.repository.UserRoleRepository;

@ExtendWith(MockitoExtension.class)
class AdminStaffServiceTest {
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private GarageRepository garageRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminStaffService adminStaffService;

    private Garage garage1;
    private Garage garage2;
    private Role staffRole;

    @BeforeEach
    void setUp() {
        adminStaffService = new AdminStaffService(
                appUserRepository,
                userRoleRepository,
                roleRepository,
                garageRepository,
                bookingRepository,
                passwordEncoder);

        garage1 = Garage.builder().id(1).name("WashMate Q7").status(GarageStatus.ACTIVE).build();
        garage2 = Garage.builder().id(2).name("WashMate Q1").status(GarageStatus.ACTIVE).build();
        staffRole = Role.builder().id(2).roleName(RoleName.STAFF).status(RecordStatus.ACTIVE).build();
    }

    private CreateStaffRequest createRequest(String role, List<Integer> garageIds) {
        return new CreateStaffRequest(
                "Nhanvien@WashMate.VN", "secret123", "  Nguyễn Văn A  ", "0901234567", role, garageIds);
    }

    private void stubHappyPathCreate() {
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(appUserRepository.existsByEmailIgnoreCase("nhanvien@washmate.vn")).thenReturn(false);
        when(appUserRepository.existsByPhone("0901234567")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("HASHED");
        when(roleRepository.findByRoleName(RoleName.STAFF)).thenReturn(Optional.of(staffRole));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(77);
            return saved;
        });
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createStaffHashesPasswordNormalizesEmailAndActivatesAccount() {
        stubHappyPathCreate();

        MeResponse response = adminStaffService.createStaff(createRequest("STAFF", List.of(1)));

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(userCaptor.capture());
        AppUser saved = userCaptor.getValue();

        assertEquals("nhanvien@washmate.vn", saved.getEmail());
        assertEquals("HASHED", saved.getPasswordHash());
        assertEquals("Nguyễn Văn A", saved.getFullName());
        // Admin tạo trực tiếp -> bỏ qua luồng OTP, tài khoản dùng được ngay.
        assertEquals(UserStatus.ACTIVE, saved.getStatus());

        assertEquals(Integer.valueOf(77), response.id());
        assertEquals(List.of("STAFF"), response.roles());
        assertEquals(List.of(1), response.garageIds());
    }

    @Test
    void createStaffAssignsOneUserRolePerGarage() {
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(garageRepository.findById(2)).thenReturn(Optional.of(garage2));
        when(appUserRepository.existsByEmailIgnoreCase("nhanvien@washmate.vn")).thenReturn(false);
        when(appUserRepository.existsByPhone("0901234567")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("HASHED");
        when(roleRepository.findByRoleName(RoleName.STAFF)).thenReturn(Optional.of(staffRole));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(77);
            return saved;
        });
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MeResponse response = adminStaffService.createStaff(createRequest("STAFF", List.of(1, 2)));

        verify(userRoleRepository, times(2)).save(any(UserRole.class));
        // userRoles là HashSet nên thứ tự không đảm bảo -> so sánh theo tập hợp.
        assertEquals(Set.of(1, 2), Set.copyOf(response.garageIds()));
    }

    // garageIds trùng lặp không được tạo 2 row user_role (vi phạm uq_user_role_per_garage).
    @Test
    void createStaffDeduplicatesGarageIds() {
        stubHappyPathCreate();

        adminStaffService.createStaff(createRequest("STAFF", List.of(1, 1, 1)));

        verify(userRoleRepository, times(1)).save(any(UserRole.class));
    }

    @Test
    void createStaffRejectsNonOperationalRole() {
        ApiException exception = assertThrows(ApiException.class,
                () -> adminStaffService.createStaff(createRequest("ADMIN", List.of(1))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createStaffRejectsUnknownRole() {
        ApiException exception = assertThrows(ApiException.class,
                () -> adminStaffService.createStaff(createRequest("SUPERVISOR", List.of(1))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createStaffRejectsMissingGarage() {
        when(garageRepository.findById(99)).thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> adminStaffService.createStaff(createRequest("STAFF", List.of(99))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createStaffRejectsSoftDeletedGarage() {
        garage1.setStatus(GarageStatus.DELETED);
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));

        ApiException exception = assertThrows(ApiException.class,
                () -> adminStaffService.createStaff(createRequest("STAFF", List.of(1))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createStaffRejectsDuplicateEmailCaseInsensitively() {
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(appUserRepository.existsByEmailIgnoreCase("nhanvien@washmate.vn")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> adminStaffService.createStaff(createRequest("STAFF", List.of(1))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createStaffRejectsDuplicatePhone() {
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(appUserRepository.existsByEmailIgnoreCase("nhanvien@washmate.vn")).thenReturn(false);
        when(appUserRepository.existsByPhone("0901234567")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> adminStaffService.createStaff(createRequest("STAFF", List.of(1))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void createStaffCreatesRoleRowWhenMissing() {
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(appUserRepository.existsByEmailIgnoreCase("nhanvien@washmate.vn")).thenReturn(false);
        when(appUserRepository.existsByPhone("0901234567")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("HASHED");
        when(roleRepository.findByRoleName(RoleName.MANAGER)).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(77);
            return saved;
        });
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MeResponse response = adminStaffService.createStaff(createRequest("MANAGER", List.of(1)));

        assertEquals(List.of("MANAGER"), response.roles());
        verify(roleRepository).save(any(Role.class));
    }

    // ── updateAssignment ─────────────────────────────────────────────

    private AppUser staffWithAssignments(Garage... garages) {
        AppUser user = AppUser.builder().id(77).fullName("Nhân viên").status(UserStatus.ACTIVE).build();
        for (Garage garage : garages) {
            user.getUserRoles().add(UserRole.builder()
                    .id(garage.getId() * 100)
                    .user(user)
                    .role(staffRole)
                    .garage(garage)
                    .status(RecordStatus.ACTIVE)
                    .build());
        }
        return user;
    }

    @Test
    void updateAssignmentReplacesGarageListAndKeepsNonOperationalRoles() {
        AppUser user = staffWithAssignments(garage1);
        Role customerRole = Role.builder().id(1).roleName(RoleName.CUSTOMER).status(RecordStatus.ACTIVE).build();
        UserRole customerAssignment = UserRole.builder()
                .id(999).user(user).role(customerRole).status(RecordStatus.ACTIVE).build();
        user.getUserRoles().add(customerAssignment);
        List<UserRole> operationalAssignments = user.getUserRoles().stream()
                .filter(userRole -> userRole.getRole().getRoleName() == RoleName.STAFF)
                .toList();

        when(garageRepository.findById(2)).thenReturn(Optional.of(garage2));
        when(appUserRepository.findWithUserRolesById(77)).thenReturn(Optional.of(user));
        when(userRoleRepository.findByUserIdAndRoleRoleNameIn(eq(77), anyCollection()))
                .thenReturn(operationalAssignments);
        when(bookingRepository.countActiveAssignmentsInGarages(eq(77), anyCollection(), anyCollection()))
                .thenReturn(0L);
        when(roleRepository.findByRoleName(RoleName.STAFF)).thenReturn(Optional.of(staffRole));
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MeResponse response = adminStaffService.updateAssignment(
                77, new UpdateStaffAssignmentRequest("STAFF", List.of(2)));

        verify(userRoleRepository).deleteAll(operationalAssignments);
        assertEquals(List.of(2), response.garageIds());
        // Role CUSTOMER không bị đụng tới.
        assertTrue(response.roles().contains("CUSTOMER"));
        assertTrue(response.roles().contains("STAFF"));
    }

    @Test
    void updateAssignmentBlocksRemovingGarageWithUnfinishedBookings() {
        AppUser user = staffWithAssignments(garage1);
        List<UserRole> operationalAssignments = List.copyOf(user.getUserRoles());

        when(garageRepository.findById(2)).thenReturn(Optional.of(garage2));
        when(appUserRepository.findWithUserRolesById(77)).thenReturn(Optional.of(user));
        when(userRoleRepository.findByUserIdAndRoleRoleNameIn(eq(77), anyCollection()))
                .thenReturn(operationalAssignments);
        when(bookingRepository.countActiveAssignmentsInGarages(eq(77), anyCollection(), anyCollection()))
                .thenReturn(3L);

        ApiException exception = assertThrows(ApiException.class, () -> adminStaffService.updateAssignment(
                77, new UpdateStaffAssignmentRequest("STAFF", List.of(2))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("3"));
        verify(userRoleRepository, never()).deleteAll(anyIterable());
        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    // Chỉ thêm chi nhánh (không gỡ) thì không cần kiểm tra booking dở dang.
    @Test
    void updateAssignmentSkipsBookingCheckWhenNothingIsRemoved() {
        AppUser user = staffWithAssignments(garage1);
        List<UserRole> operationalAssignments = List.copyOf(user.getUserRoles());

        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(garageRepository.findById(2)).thenReturn(Optional.of(garage2));
        when(appUserRepository.findWithUserRolesById(77)).thenReturn(Optional.of(user));
        when(userRoleRepository.findByUserIdAndRoleRoleNameIn(eq(77), anyCollection()))
                .thenReturn(operationalAssignments);
        when(roleRepository.findByRoleName(RoleName.STAFF)).thenReturn(Optional.of(staffRole));
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MeResponse response = adminStaffService.updateAssignment(
                77, new UpdateStaffAssignmentRequest("STAFF", List.of(1, 2)));

        verify(bookingRepository, never()).countActiveAssignmentsInGarages(any(), anyCollection(), anyCollection());
        assertEquals(Set.of(1, 2), Set.copyOf(response.garageIds()));
    }

    @Test
    void updateAssignmentRejectsUnknownUser() {
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(appUserRepository.findWithUserRolesById(404)).thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class, () -> adminStaffService.updateAssignment(
                404, new UpdateStaffAssignmentRequest("STAFF", List.of(1))));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void updateAssignmentRejectsDeletedUser() {
        AppUser user = staffWithAssignments(garage1);
        user.setStatus(UserStatus.DELETED);
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage1));
        when(appUserRepository.findWithUserRolesById(77)).thenReturn(Optional.of(user));

        ApiException exception = assertThrows(ApiException.class, () -> adminStaffService.updateAssignment(
                77, new UpdateStaffAssignmentRequest("STAFF", List.of(1))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(userRoleRepository, never()).deleteAll(anyIterable());
    }
}
