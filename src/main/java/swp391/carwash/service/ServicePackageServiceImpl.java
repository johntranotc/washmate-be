package swp391.carwash.service;


import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.request.ServicePackageRequest.CreateServicePackageRequest;
import swp391.carwash.dto.request.ServicePackageRequest.UpdateServicePackageRequest;
import swp391.carwash.dto.response.ServicePackage.ServicePackageResponse;
import swp391.carwash.entity.ServicePackage;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.repository.ServicePackageRepository;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.entity.Garage;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ServicePackageServiceImpl implements ServicePackageService {

    private final ServicePackageRepository servicePackageRepository;
    private final GarageRepository garageRepository;

    @Override
    @Transactional
    public ServicePackageResponse createService(CreateServicePackageRequest request) {
        Garage garage = garageRepository.findById(request.getGarageId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy Garage với ID: " + request.getGarageId()));

        // 1. Tạo Entity từ Request DTO thông qua Builder pattern
        ServicePackage servicePackage = ServicePackage.builder()
                .garage(garage)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .duration(request.getDurationMinutes())
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();

        // 2. Lưu thực thể xuống Database
        ServicePackage saved = servicePackageRepository.save(servicePackage);

        // 3. Chuyển đổi và trả về Response DTO sạch cho Controller
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServicePackageResponse> getServicesByGarageId(Long garageId) {
        List<ServicePackage> services = servicePackageRepository.findByGarageId(garageId.intValue());
        return services.stream()
                // Không trả gói đã xoá: trước đây khách thấy cả DELETED rồi chọn xong mới bị
                // BookingService từ chối "Service package is not active".
                .filter(service -> service.getStatus() != RecordStatus.DELETED)
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ServicePackageResponse getServiceById(Long id) {
        ServicePackage servicePackage = servicePackageRepository.findById(id.intValue())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy gói dịch vụ với ID: " + id));
        return mapToResponse(servicePackage);
    }

    @Override
    @Transactional
    public ServicePackageResponse updateService(Long id, UpdateServicePackageRequest request) {
        ServicePackage servicePackage = servicePackageRepository.findById(id.intValue())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy gói dịch vụ với ID: " + id));

        // Tiến hành cập nhật thông tin mới từ Request DTO vào Entity
        servicePackage.setName(request.getName());
        servicePackage.setDescription(request.getDescription());
        servicePackage.setPrice(request.getPrice());
        servicePackage.setDuration(request.getDurationMinutes());
        // DTO đã giới hạn @Pattern ACTIVE|INACTIVE, nhưng vẫn parse an toàn để null/giá trị lạ
        // không thành NPE/500 nếu sau này có caller khác.
        servicePackage.setStatus(parseStatus(request.getStatus()));


        ServicePackage updated = servicePackageRepository.save(servicePackage);
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void deleteService(Long id) {
        ServicePackage servicePackage = servicePackageRepository.findById(id.intValue())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy gói dịch vụ với ID: " + id));

        // SOFT DELETE, không xoá cứng: booking.service_id là FK NOT NULL nên xoá cứng một gói
        // đã từng có đơn sẽ vi phạm khoá ngoại (hoặc phá lịch sử đơn nếu FK bị nới).
        // Cả hệ thống dùng RecordStatus.DELETED, riêng chỗ này trước đây xoá cứng.
        if (servicePackage.getStatus() == RecordStatus.DELETED) {
            return; // idempotent
        }
        servicePackage.setStatus(RecordStatus.DELETED);
        servicePackageRepository.save(servicePackage);
    }

    private RecordStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Trạng thái không được để trống");
        }
        try {
            RecordStatus parsed = RecordStatus.valueOf(status.trim().toUpperCase());
            if (parsed == RecordStatus.DELETED) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Hãy dùng API DELETE để xoá gói dịch vụ");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Trạng thái '" + status + "' không hợp lệ. Chỉ nhận ACTIVE hoặc INACTIVE.");
        }
    }

    // Hàm Mapper nội bộ biến thực thể Entity thành Response DTO an toàn
    private ServicePackageResponse mapToResponse(ServicePackage entity) {
        return ServicePackageResponse.builder()
                .servicePackageId(entity.getId())
                .garageId(entity.getGarage().getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .price(entity.getPrice())
                .durationMinutes(entity.getDuration())
                .status(entity.getStatus().name())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}