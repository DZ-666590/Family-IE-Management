package com.familyfinance.accounting;

import java.nio.file.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** First multi-currency release stays CNY-only until the receiver commits its
 * successful readiness result. A failed rollout cannot leave foreign writes
 * behind for the previous CNY-only binary to misinterpret. */
@Component
public class DeploymentRevisionGate {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final boolean required;private final Path state;private final String expected;
 @Autowired
 public DeploymentRevisionGate(@Value("${app.multicurrency.verify-deployed-revision:true}") boolean required,
         @Value("${app.multicurrency.deployment-state:/var/lib/family-finance-ci/current.json}") String state){
  this(required,Path.of(state),packagedRevision());
 }
 DeploymentRevisionGate(boolean required,Path state,String expected){this.required=required;this.state=state;this.expected=expected;}
 public boolean ready(){
  if(!required)return true;
  if(expected==null||!expected.matches("[a-f0-9]{40}"))return false;
  try {return Files.isRegularFile(state)&&Files.size(state)<=16384&&expected.equals(JSON.readTree(Files.readString(state)).path("commit").asText());}
  catch(Exception unavailable){return false;}
 }
 public void requireCompatibleUnitPrice(java.math.BigDecimal price){
  if(price.stripTrailingZeros().scale()>2&&!ready())throw new IllegalArgumentException("新版本仍在部署验证，暂不能保存超过两位小数的单价，请稍后重试");
 }
 private static String packagedRevision(){
  try(var input=new ClassPathResource("static/deployment.json").getInputStream()){return JSON.readTree(input).path("commit").asText();}
  catch(Exception unavailable){return null;}
 }
}
