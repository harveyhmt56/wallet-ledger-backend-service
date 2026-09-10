package com.example.walletledger.configuration;

import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.util.Assert;

/** Prevent an incomplete nonlocal deployment from silently relaxing token restrictions. */
final class RequiredJwtConfiguration {
  private RequiredJwtConfiguration() {}

  static void validate(OAuth2ResourceServerProperties.Jwt jwt) {
    Assert.hasText(
        jwt.getIssuerUri(),
        "spring.security.oauth2.resourceserver.jwt.issuer-uri is required outside the local profile");
    Assert.notEmpty(
        jwt.getAudiences(),
        "spring.security.oauth2.resourceserver.jwt.audiences must contain at least one audience outside the local profile");
    for (String audience : jwt.getAudiences()) {
      Assert.hasText(
          audience,
          "spring.security.oauth2.resourceserver.jwt.audiences must not contain blank values");
    }
  }
}
