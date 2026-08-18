package models;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SandboxInstance {
  private String id;
  private String name;
  private String civiformVersion;
  private SandboxStatus status;
  private String url;
  private String adminEmail;
  private String notes;
  private Instant createdAt;
  private Instant expiresAt;
}
