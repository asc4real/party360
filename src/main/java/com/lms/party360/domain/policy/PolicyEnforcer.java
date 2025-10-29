package com.lms.party360.domain.policy;

import com.lms.party360.api.controller.PartyController;
import io.spring.gradle.dependencymanagement.org.apache.maven.artifact.repository.Authentication;

public class PolicyEnforcer {
    public void allow(Authentication auth, String create, PartyController.ResourceRef resourceRef) {
    }
}
