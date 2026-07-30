package swp391.carwash.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.dto.request.vehicles.CreateMyVehicleRequest;
import swp391.carwash.dto.request.vehicles.CreateVehicleRequest;
import swp391.carwash.dto.request.vehicles.UpdateVehicleRequest;
import swp391.carwash.dto.response.vehicles.VehicleResponse;
import swp391.carwash.security.AppUserDetails;

import java.util.List;

@Service
@Transactional
public interface VehicleService {

    VehicleResponse create(CreateVehicleRequest request);

    List<VehicleResponse> getAll();

    List<VehicleResponse> getByUserId(Integer userId);

    /**
     * Sửa xe. {@code principal} là BẮT BUỘC để kiểm tra quyền sở hữu: trước đây thiếu tham số
     * này nên bất kỳ user đăng nhập nào cũng sửa được xe của người khác chỉ bằng cách đoán
     * vehicleId.
     */
    VehicleResponse update(Integer vehicleId, UpdateVehicleRequest request, AppUserDetails principal);

    /** Xoá mềm xe. Xem ghi chú về {@code principal} ở {@link #update}. */
    void delete(Integer vehicleId, AppUserDetails principal);

    List<VehicleResponse> getByEmail(String email);

    VehicleResponse createVehicleForUser(Integer userId, CreateMyVehicleRequest request);

}
