package com.familyfinance.ai;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class AiSystemConfigurationTest {
 @Test void rejectsMissingKeysAndNonOfficialDestinations() {
  String official="https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
  assertThat(new AiSystemConfiguration(official, "").ready()).isFalse();
  for (String url : new String[]{"http://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", "https://evil.example.com/compatible-mode/v1", official+"?key=secret", official.replace(".com/", ".com.evil.org/")}) {
   var config = new AiSystemConfiguration(url,"test-secret");
   assertThat(config.ready()).isFalse();
   assertThatThrownBy(config::credential).isInstanceOf(AiFailure.class);
  }
  var config = new AiSystemConfiguration(official,"test-secret");
  assertThat(config.ready()).isTrue();
  assertThat(config.credential().model()).isEqualTo("qwen3.8-max");
  assertThat(config.credential().toString()).doesNotContain("test-secret");
 }
}
