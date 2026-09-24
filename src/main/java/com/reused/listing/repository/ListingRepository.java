package com.reused.listing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.reused.listing.entity.Listing;

public interface ListingRepository extends JpaRepository<Listing, Long> {

	Optional<Listing> findByIdAndDeletedAtIsNull(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Listing l where l.id = :id and l.deletedAt is null")
	Optional<Listing> findActiveByIdForUpdate(@Param("id") Long id);

}
