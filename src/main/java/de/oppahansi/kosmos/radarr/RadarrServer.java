package de.oppahansi.kosmos.radarr;

import de.oppahansi.kosmos.common.KosmosEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** A configured Radarr server to import an already-managed movie library from. */
@Entity
@Table(name = "radarr_server")
public class RadarrServer extends KosmosEntity {

  @Column(nullable = false, length = 200)
  public String name;

  @Column(name = "base_url", nullable = false, length = 1000)
  public String baseUrl;

  @Column(name = "api_key", nullable = false, length = 200)
  public String apiKey;

  @Column(nullable = false)
  public boolean enabled;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
