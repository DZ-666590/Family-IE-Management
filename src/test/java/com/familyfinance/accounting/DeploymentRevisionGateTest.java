package com.familyfinance.accounting;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class DeploymentRevisionGateTest {
 @TempDir Path temp;
 @Test void foreignWritesOpenOnlyAfterThisExactReleaseIsVerified()throws Exception{
  Path state=temp.resolve("current.json");String commit="a".repeat(40);
  var gate=new DeploymentRevisionGate(true,state,commit);
  assertThat(gate.ready()).isFalse();
  assertThatThrownBy(()->gate.requireCompatibleUnitPrice(new java.math.BigDecimal("0.001234"))).isInstanceOf(IllegalArgumentException.class);
  assertThatCode(()->gate.requireCompatibleUnitPrice(new java.math.BigDecimal("1.230000"))).doesNotThrowAnyException();
  Files.writeString(state,"{\"commit\":\""+"b".repeat(40)+"\"}");assertThat(gate.ready()).isFalse();
  Files.writeString(state,"{\"commit\":\""+commit+"\"}");assertThat(gate.ready()).isTrue();
  assertThatCode(()->gate.requireCompatibleUnitPrice(new java.math.BigDecimal("0.001234"))).doesNotThrowAnyException();
  Files.writeString(state,"not json");assertThat(gate.ready()).isFalse();
 }
}
