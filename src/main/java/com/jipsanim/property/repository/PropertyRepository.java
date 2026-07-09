package com.jipsanim.property.repository;

import com.jipsanim.property.domain.Property;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    @EntityGraph(attributePaths = {"images", "realtor"})
    Optional<Property> findWithImagesById(Long id);
}
