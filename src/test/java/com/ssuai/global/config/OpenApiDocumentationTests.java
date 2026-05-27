package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class OpenApiDocumentationTests {

    private final MockMvc mockMvc;

    @Autowired
    OpenApiDocumentationTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void exposesOpenApiDocumentForRestEndpoints() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"title\":\"ssuAI Backend API\"")
                .contains("\"/api/meals/today\"")
                .contains("\"/api/dorm/meals/this-week\"")
                .contains("\"/api/campus/facilities\"");
    }
}
