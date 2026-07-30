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

import swp391.carwash.dto.request.Garages.CreateGarageRequest;
import swp391.carwash.dto.response.Garages.GarageResponse;
import swp391.carwash.service.GarageService;

import java.time.LocalDateTime;
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
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class GarageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @MockitoBean
    private GarageService garageService;

    @Test
    void testCreateGarageSuccess() throws Exception {
        CreateGarageRequest request = new CreateGarageRequest("Garage A", "123 Street", "0123456789");
        
        GarageResponse response = new GarageResponse(1, "Garage A", "123 Street", "0123456789", "ACTIVE", LocalDateTime.now());

        when(garageService.createGarage(any(CreateGarageRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/garages")
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Garage A"))
                .andExpect(jsonPath("$.address").value("123 Street"));
    }

    @Test
    void testCreateGarageValidationFailed() throws Exception {
        CreateGarageRequest request = new CreateGarageRequest("", "123 Street", "012");
        // Missing name and invalid phone

        mockMvc.perform(post("/api/v1/garages")
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetAllGaragesPublic() throws Exception {
        when(garageService.getAllGarages()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/garages"))
                .andExpect(status().isOk());
    }

    @Test
    void testDeleteGarageSuccess() throws Exception {
        doNothing().when(garageService).deleteGarage(eq(1));

        mockMvc.perform(delete("/api/v1/garages/1")
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void testDeleteGarageNotFound() throws Exception {
        doThrow(new RuntimeException("Not Found")).when(garageService).deleteGarage(eq(999));

        mockMvc.perform(delete("/api/v1/garages/999")
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf()))
                .andExpect(status().isNotFound());
    }
}
