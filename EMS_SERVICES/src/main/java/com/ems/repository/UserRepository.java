package com.ems.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findAllByProfilePhotoKeyIsNotNull();

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByUserId(String userId);

    Optional<User> findByUserIdIgnoreCase(String userId);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByMobileNumber(String mobileNumber);

    boolean existsByUserId(String userId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select u from User u where u.id = :id")
        Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select u from User u
            where (:searchText is null
            or lower(u.email) like :searchText
            or lower(u.userId) like :searchText
            or lower(u.firstName) like :searchText
            or lower(u.lastName) like :searchText)
            and (:enabled is null or u.enabled = :enabled)
            order by u.id asc
            """)
    List<User> search(
            @Param("searchText") String searchText,
            @Param("enabled") Boolean enabled);

    /**
     * Stamps a successful sign-in. A bulk update rather than a save, so that
     * logging in is not recorded as an edit to the account in updated_by/updated_date.
     */
    @Modifying
    @Query("update User u set u.lastLoginAt = :lastLoginAt where u.id = :id")
    int updateLastLoginAt(@Param("id") Long id, @Param("lastLoginAt") Instant lastLoginAt);
}
