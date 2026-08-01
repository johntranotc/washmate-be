package swp391.carwash.service;

import swp391.carwash.dto.request.ServicePackageRequest.CreateServicePackageRequest;
import swp391.carwash.dto.request.ServicePackageRequest.UpdateServicePackageRequest;
import swp391.carwash.dto.response.ServicePackage.ServicePackageResponse;
import swp391.carwash.security.AppUserDetails;

import java.util.List;

public interface ServicePackageService {
    // Các thao tác ghi nhận principal: @PreAuthorize ở controller chỉ lọc được ROLE, không
    // biết người đó phụ trách garage nào. Gói dịch vụ quyết định GIÁ của booking nên phạm vi
    // garage bắt buộc phải kiểm ở tầng service.
    ServicePackageResponse createService(CreateServicePackageRequest request, AppUserDetails principal);
    List<ServicePackageResponse> getServicesByGarageId(Long garageId);
    ServicePackageResponse getServiceById(Long id);
    ServicePackageResponse updateService(Long id, UpdateServicePackageRequest request, AppUserDetails principal);
    void deleteService(Long id, AppUserDetails principal);
}