package com.leenax.studio.scripts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScriptRepository extends JpaRepository<Script, UUID> {

    List<Script> findTop100ByOrderByUpdatedAtDesc();

    List<Script> findTop100ByProjectIdOrderByUpdatedAtDesc(UUID projectId);

    List<Script> findByParentId(UUID parentId);
}
