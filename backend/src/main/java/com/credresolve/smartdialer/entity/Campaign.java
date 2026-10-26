package com.credresolve.smartdialer.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "campaigns")
public class Campaign {
  @Id public UUID id;
  public String name;
  public String mode;
  public String status;
  public String provider;

  @Column(name = "max_active")
  public int maxActive;

  @Column(name = "risk_limit")
  public double riskLimit;

  @Column(name = "answer_rate")
  public double answerRate;

  protected Campaign() {}
}
