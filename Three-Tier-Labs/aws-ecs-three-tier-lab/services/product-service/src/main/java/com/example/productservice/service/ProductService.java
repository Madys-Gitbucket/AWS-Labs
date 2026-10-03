package com.example.productservice.service;

import com.example.productservice.model.Product;
import com.example.productservice.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public List<Product> findAll() { return repository.findAll(); }
    public Optional<Product> findById(Long id) { return repository.findById(id); }
    public List<Product> findByCategory(String category) { return repository.findByCategory(category); }
    public Product save(Product product) { return repository.save(product); }
}
