package com.leenax.studio.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(String email);

    List<User> findAllByOrderByCreatedAtAsc();

    long countByStatusIn(java.util.Collection<User.Status> statuses);

    List<User> findByStatusOrderByDisplayNameAsc(User.Status status);
}
