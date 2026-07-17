package com.stokr.arbitrage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoExecSettingRepository extends JpaRepository<AutoExecSetting, Long> {
    Optional<AutoExecSetting> findBySettingKey(String settingKey);
    List<AutoExecSetting> findAllByOrderBySettingKey();
}
