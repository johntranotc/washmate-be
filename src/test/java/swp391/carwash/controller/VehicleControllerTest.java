package swp391.carwash.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mail.javamail.JavaMailSender;

import swp391.carwash.dto.request.vehicles.CreateVehicleRequest;
import swp391.carwash.dto.response.vehicles.VehicleResponse;
import swp391.carwash.service.VehicleService;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.entity.AppUser;
import swp391.carwash.enums.UserStatus;

import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class VehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @MockitoBean
    private VehicleService vehicleService;

    private AppUserDetails createMockUser() {
        AppUser appUser = new AppUser();
        appUser.setId(1);
        appUser.setEmail("user@example.com");
        appUser.setFullName("Test User");
        appUser.setPhone("0123456789");
        appUser.setStatus(UserStatus.ACTIVE);
        appUser.setUserRoles(Collections.emptySet());
        return new AppUserDetails(appUser);
    }

    @Test
    void testCreateVehicleSuccess() throws Exception {
        CreateVehicleRequest request = new CreateVehicleRequest();
        request.setUserId(1);
        request.setLicensePlate("29A-12345");
        request.setBrand("Toyota");
        request.setModel("Camry");
        request.setColor("Black");

        VehicleResponse response = new VehicleResponse();
        response.setVehicleId(100);
        response.setLicensePlate("29A-12345");
        response.setBrand("Toyota");
        response.setModel("Camry");
        response.setColor("Black");

        when(vehicleService.create(any(CreateVehicleRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/vehicles")
                .with(user(createMockUser()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vehicleId").value(100))
                .andExpect(jsonPath("$.licensePlate").value("29A-12345"));
    }

    @Test
    void testCreateVehicleValidationFailed() throws Exception {
        CreateVehicleRequest request = new CreateVehicleRequest();
        // Missing license plate to trigger validation error
        request.setUserId(1);
        request.setBrand("Toyota");
        request.setModel("Camry");
        request.setColor("Black");

        mockMvc.perform(post("/api/v1/vehicles")
                .with(user(createMockUser()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetVehiclesByUserIdUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/user/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testDeleteVehicleSuccess() throws Exception {
        // delete() nay nhận thêm principal để kiểm quyền sở hữu (chống IDOR).
        doNothing().when(vehicleService).delete(eq(100), any());

        mockMvc.perform(delete("/api/v1/vehicles/100")
                .with(user(createMockUser()))
                .with(csrf()))
                .andExpect(status().isNoContent());
    }
}
