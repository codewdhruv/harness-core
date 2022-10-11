/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.delegate.task.winrm;

import static io.harness.annotations.dev.HarnessModule._930_DELEGATE_TASKS;
import static io.harness.annotations.dev.HarnessTeam.CDP;
import static io.harness.rule.OwnerRule.BOJAN;
import static io.harness.rule.OwnerRule.FERNANDOD;
import static io.harness.rule.OwnerRule.INDER;
import static io.harness.rule.OwnerRule.JELENA;
import static io.harness.rule.OwnerRule.ROHITKARELIA;
import static io.harness.rule.OwnerRule.SAHIL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.harness.CategoryTest;
import io.harness.annotations.dev.OwnedBy;
import io.harness.annotations.dev.TargetModule;
import io.harness.category.element.UnitTests;
import io.harness.logging.LogCallback;
import io.harness.rule.Owner;

import software.wings.beans.WinRmCommandParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@OwnedBy(CDP)
@TargetModule(_930_DELEGATE_TASKS)
public class DefaultWinRmExecutorTest extends CategoryTest {
  @Mock LogCallback logCallback;
  @Mock WinRmSessionConfig config;
  @Mock WinRmSession winRmSession;
  private DefaultWinRmExecutor spyDefaultWinRmExecutor;
  String simpleCommand;
  String reallyLongCommand;
  String echoCommand;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    spyDefaultWinRmExecutor = new DefaultWinRmExecutor(logCallback, true, config, false, false);
    simpleCommand = "$test=\"someruntimepath\"\n"
        + "echo $test\n"
        + "if($test){\n"
        + "    Write-Host \"i am inside if\"\n"
        + "} else {\n"
        + "    Write-Host \"i am inside else\"\n"
        + "}";

    reallyLongCommand = simpleCommand + simpleCommand + simpleCommand + simpleCommand
        + "$myfile = Get-Content -Path \"C:\\Users\\rohit_karelia\\logontest.ps1\" | Get-Unique | Measure-Object \n"
        + "echo $myfile";

    echoCommand = "echo test";
  }

  @Test
  @Owner(developers = ROHITKARELIA)
  @Category(UnitTests.class)
  public void testConstructPSScriptWithCommands() {
    List<List<String>> result1 = WinRmExecutorHelper.constructPSScriptWithCommands(
        simpleCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result1.size()).isEqualTo(1);

    List<List<String>> result2 = WinRmExecutorHelper.constructPSScriptWithCommands(
        reallyLongCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result2.size()).isEqualTo(2);
    verify(config, times(1)).isUseNoProfile();
    assertThat(config.getCommandParameters()).isEmpty();
  }

  @Test
  @Owner(developers = BOJAN)
  @Category(UnitTests.class)
  public void testConstructCommandsListWithCommands() {
    List<String> result1 = WinRmExecutorHelper.constructCommandsList(
        simpleCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result1.size()).isEqualTo(1);

    List<String> result2 = WinRmExecutorHelper.constructCommandsList(
        reallyLongCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result2.size()).isEqualTo(1);

    String commandOver4KB = "";
    for (int i = 0; i < 500; i++) {
      commandOver4KB += "0123456789";
    }

    List<String> result3 = WinRmExecutorHelper.constructCommandsList(
        commandOver4KB, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result3.size()).isEqualTo(2);
    verify(config, times(1)).isUseNoProfile();
    assertThat(config.getCommandParameters()).isEmpty();
  }

  @Test
  @Owner(developers = SAHIL)
  @Category(UnitTests.class)
  public void testConstructPSScriptWithCommandsWithoutProfile() {
    when(config.isUseNoProfile()).thenReturn(true);
    List<List<String>> result1 = WinRmExecutorHelper.constructPSScriptWithCommands(
        simpleCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result1.size()).isEqualTo(1);

    List<List<String>> result2 = WinRmExecutorHelper.constructPSScriptWithCommands(
        reallyLongCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result2.size()).isEqualTo(2);

    verify(config, times(1)).isUseNoProfile();
  }

  @Test
  @Owner(developers = ROHITKARELIA)
  @Category(UnitTests.class)
  public void testCleanUpFilesDisableEncodingFFOn() {
    DefaultWinRmExecutor defaultWinRmExecutorFFOn = new DefaultWinRmExecutor(logCallback, true, config, true, false);
    WinRmExecutorHelper.cleanupFiles(winRmSession, "PSFileName.ps1", DefaultWinRmExecutor.POWERSHELL, true, null);
    verify(winRmSession, times(1)).executeCommandString(any(), any(), any(), eq(false));
  }

  @Test
  @Owner(developers = SAHIL)
  @Category(UnitTests.class)
  public void testpsWrappedCommandWithEncodingWithProfile() {
    when(config.isUseNoProfile()).thenReturn(true);
    spyDefaultWinRmExecutor = new DefaultWinRmExecutor(logCallback, true, config, true, false);
    String poweshellCommand = WinRmExecutorHelper.psWrappedCommandWithEncoding(
        simpleCommand, DefaultWinRmExecutor.POWERSHELL_NO_PROFILE, null);
    assertThat(poweshellCommand.contains("NoProfile")).isTrue();
  }

  @Test
  @Owner(developers = SAHIL)
  @Category(UnitTests.class)
  public void testpsWrappedCommandWithEncodingWithoutProfile() {
    when(config.isUseNoProfile()).thenReturn(false);
    String poweshellCommand =
        WinRmExecutorHelper.psWrappedCommandWithEncoding(simpleCommand, DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(poweshellCommand.contains("NoProfile")).isFalse();
  }

  @Test
  @Owner(developers = INDER)
  @Category(UnitTests.class)
  public void testConstructPSScriptWithCommandsWithAmpersand() {
    String command = "echo \"1&2\"";
    List<List<String>> result = WinRmExecutorHelper.constructPSScriptWithCommands(
        command, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, null);
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.get(0)).hasSize(2);
    Pattern patternForAmpersandWithinString = Pattern.compile("[a-zA-Z0-9]+\\^&");
    assertThat(patternForAmpersandWithinString.matcher(result.get(0).get(1)).find()).isTrue();
  }

  @Test
  @Owner(developers = JELENA)
  @Category(UnitTests.class)
  public void testConstructPSScriptWithCommandsAndCommandParameters() {
    List<WinRmCommandParameter> commandParameters = new ArrayList<>();
    commandParameters.add(new WinRmCommandParameter("ComputerName", "TestComputerName"));
    commandParameters.add(new WinRmCommandParameter("ConfigurationName", "TestConfigurationName"));

    String expectedString = "Invoke-Command -ComputerName TestComputerName -ConfigurationName TestConfigurationName";
    List<List<String>> result1 = WinRmExecutorHelper.constructPSScriptWithCommands(
        echoCommand, "tempPSScript.ps1", DefaultWinRmExecutor.POWERSHELL, commandParameters);
    assertThat(result1.size()).isEqualTo(1);
    assertThat((result1.get(0).get(0)).contains(expectedString)).isTrue();
  }

  @Test
  @Owner(developers = JELENA)
  @Category(UnitTests.class)
  public void testWrappedCommandWithEncodingWithAdditionalParameters() {
    List<WinRmCommandParameter> commandParameters = new ArrayList<>();
    commandParameters.add(new WinRmCommandParameter("ComputerName", "TestComputerName"));
    commandParameters.add(new WinRmCommandParameter("ConfigurationName", "TestConfigurationName"));

    String expectedString = "Invoke-Command -ComputerName TestComputerName -ConfigurationName TestConfigurationName";

    when(config.isUseNoProfile()).thenReturn(true);
    spyDefaultWinRmExecutor = new DefaultWinRmExecutor(logCallback, true, config, true, false);
    String poweshellCommand = WinRmExecutorHelper.psWrappedCommandWithEncoding(
        simpleCommand, DefaultWinRmExecutor.POWERSHELL_NO_PROFILE, commandParameters);
    assertThat(poweshellCommand.contains(expectedString)).isTrue();
  }

  @Test
  @Owner(developers = FERNANDOD)
  @Category(UnitTests.class)
  public void shouldNotChangeCommandWhenAddEnvVariablesCollectorWithoutEnvVariables() {
    String outputFileName = "%TEMP%\\harness.out";
    String writeHost = "Write-Host $env:HTTP_PROXY";

    final String result =
        spyDefaultWinRmExecutor.addEnvVariablesCollector(writeHost, Collections.emptyList(), outputFileName);
    assertThat(result).startsWith(writeHost);
    assertThat(result).doesNotContain(outputFileName);
  }

  @Test
  @Owner(developers = FERNANDOD)
  @Category(UnitTests.class)
  public void shouldChangeCommandWhenAddEnvVariablesCollector() {
    String outputFileName = "%TEMP%\\harness.out";
    String writeHost = "Write-Host $env:HTTP_PROXY";
    List<String> envVariables = Arrays.asList("var1", "var2");

    final String result = spyDefaultWinRmExecutor.addEnvVariablesCollector(writeHost, envVariables, outputFileName);
    assertThat(result).startsWith(writeHost);
    assertThat(result).contains(outputFileName);
    assertThat(result).contains("$e+=$Env:var1\n Write-Output $e | Out-File -Encoding UTF8 -append -FilePath");
    assertThat(result).contains("$e+=$Env:var2\n Write-Output $e | Out-File -Encoding UTF8 -append -FilePath");
    assertThat(result).contains("Write-Output \"__NL\" | Out-File -Encoding UTF8 -append -FilePath");
  }
}
