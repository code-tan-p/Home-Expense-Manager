package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.finance.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    @Query("select i from SettlementItem i where i.version.id = :versionId order by i.lineNo")
    List<SettlementItem> findByVersionId(Long versionId);

}
