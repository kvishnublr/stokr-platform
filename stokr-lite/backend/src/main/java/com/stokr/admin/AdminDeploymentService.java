package com.stokr.admin;

import com.stokr.engine.Deployment;
import com.stokr.engine.DeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDeploymentService {

    private final DeploymentService deploymentService;

    public List<Deployment> getAllDeployments() {
        return deploymentService.getAllActiveDeployments();
    }

    @Transactional
    public Deployment forceStop(Long deploymentId) {
        return deploymentService.forceStopDeployment(deploymentId);
    }

    @Transactional
    public void stopAllDeployments() {
        List<Deployment> all = deploymentService.getAllActiveDeployments();
        for (Deployment d : all) {
            deploymentService.forceStopDeployment(d.getId());
        }
    }

    public long getActiveDeploymentCount() {
        return deploymentService.getAllActiveDeployments().size();
    }
}
