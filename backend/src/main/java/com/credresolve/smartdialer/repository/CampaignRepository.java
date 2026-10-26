package com.credresolve.smartdialer.repository;

import com.credresolve.smartdialer.entity.Campaign;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {}
