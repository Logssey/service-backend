package com.reused.listing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.listing.entity.Listing;

public interface ListingRepository extends JpaRepository<Listing, Long> {

	Optional<Listing> findByIdAndDeletedAtIsNull(Long id);

}
