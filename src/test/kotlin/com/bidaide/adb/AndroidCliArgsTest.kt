package com.bidaide.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AndroidCliArgsTest {
    @Test
    fun `install targets the device with a full install accepting test and downgrade builds`() {
        assertEquals(
            listOf("install", "--device=emulator-5554", "--use-delta-install=false", "--install-options=-t -d", "--apks=/tmp/app.apk"),
            AndroidCli.installArgs("emulator-5554", File("/tmp/app.apk")),
        )
    }

    @Test
    fun `screenshot writes to the given file`() {
        assertEquals(
            listOf("screen", "capture", "--device=R58M", "--output=/tmp/s.png"),
            AndroidCli.screenshotArgs("R58M", File("/tmp/s.png")),
        )
    }

    @Test
    fun `text goes to the focused element and submit is optional`() {
        assertEquals(listOf("input", "--device=R58M", "text", "hola señor", "focused=true"), AndroidCli.inputTextArgs("R58M", "hola señor", false))
        assertEquals(listOf("input", "--device=R58M", "text", "x", "focused=true", "--submit"), AndroidCli.inputTextArgs("R58M", "x", true))
    }

    @Test
    fun `device hub targets one device or lets the hub choose`() {
        assertEquals(listOf("device", "hub", "--device=emulator-5554"), AndroidCli.deviceHubArgs("emulator-5554"))
        assertEquals(listOf("device", "hub"), AndroidCli.deviceHubArgs(null))
    }
}
