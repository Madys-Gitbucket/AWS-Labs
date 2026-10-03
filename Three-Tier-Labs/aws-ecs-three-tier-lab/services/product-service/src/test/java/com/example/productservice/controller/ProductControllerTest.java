package com.example.productservice.controller;

import com.example.productservice.model.Product;
import com.example.productservice.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ProductService productService;
    @Autowired ObjectMapper objectMapper;

    @Test
    void listAllReturnsProducts() throws Exception {
        Product p = new Product("Widget", "Hardware", 9.99, 100);
        p.setId(1L);
        when(productService.findAll()).thenReturn(List.of(p));
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Widget"));
    }

    @Test
    void getByIdReturns404WhenNotFound() throws Exception {
        when(productService.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/products/99")).andExpect(status().isNotFound());
    }

    @Test
    void createReturns201() throws Exception {
        Product p = new Product("Gadget", "Electronics", 29.99, 50);
        p.setId(2L);
        when(productService.save(any())).thenReturn(p);
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(p)))
                .andExpect(status().isCreated());
    }
}
