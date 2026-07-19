package com.visionary.repository;

import com.visionary.entity.SharedTextbook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharedTextbookRepository extends JpaRepository<SharedTextbook, Long> {

    List<SharedTextbook> findByReviewStatusAndVisibilityOrderByGmtCreatedDesc(
            String reviewStatus,
            String visibility
    );

    List<SharedTextbook> findByOwnerUserIdOrderByGmtCreatedDesc(Long ownerUserId);

    List<SharedTextbook> findByReviewStatusOrderByGmtCreatedAsc(String reviewStatus);
}
