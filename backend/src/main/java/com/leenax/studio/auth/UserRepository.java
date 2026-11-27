package com.leenax.studio.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(String email);

    @Query("select u from User u where (:status is null or u.status = :status) " +
            "and (:search is null or lower(u.displayName) like lower(concat(:search, '%')) " +
            "or lower(u.email) like lower(concat(:search, '%'))) order by u.createdAt asc")
    Page<User> searchUsers(@Param("status") User.Status status, @Param("search") String search, Pageable pageable);

    long countByRoleAndStatusAndIdNot(User.Role role, User.Status status, java.util.UUID id);

}
