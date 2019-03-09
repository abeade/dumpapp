package com.abeade.stetho

import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.math.absoluteValue
import kotlin.system.exitProcess

fun main(args : Array<String>) {
    var commands = args
    // Manually parse out -p <process>, all other option handling occurs inside
    // the hosting process.

    // Connect to the process passed in via -p. If that is not supplied fallback
    // the process defined in STETHO_PROCESS. If neither are defined throw.
    var process = System.getenv("STETHO_PROCESS")
    if (args.isNotEmpty() && (args[0] == "-p" || args[0] == "--process")) {
        if (args.size < 2) {
            println("Missing <process>")
            exitProcess(1)
        } else {
            process = args[2]
            commands = args.copyOfRange(3, args.size)
        }
    }

    // Connect to ANDROID_SERIAL if supplied, otherwise fallback to any
    // transport.
    val device = System.getenv("ANDROID_SERIAL")

    // Connect on the overridden port if specified
    val port = getAdbServerPort()

    try {
        val struct = Struct()
        val adbSock = stethoOpen(device, process, port)

        // Send dumpapp hello (DUMP + version=1)
        adbSock.outStream.write("DUMP".toByteArray() + struct.pack("!i", 1))

        var enterFrame = "!".toByteArray() + struct.pack("!i", commands.size.toLong())
        for (command in commands.map { it.toByteArray(StandardCharsets.UTF_8) }) {
            enterFrame += struct.pack("!H", command.size.toLong())
            enterFrame += command
        }
        adbSock.outStream.write(enterFrame)
        readFrames(adbSock, struct)
    } catch (e: HumanReadableException) {
        println(e.reason)
        exitProcess(1)
    } catch (e: Exception) {
        e.printStackTrace()
        exitProcess(2)
    }
}

fun readFrames(adbSock: AdbSmartSocketClient, struct: Struct) {
    while (true) {
        // All frames have a single character code followed by a big-endian int
        val code = adbSock.readInput(1, "code")
        val n = struct.unpack("!i", adbSock.readInput(4, "int4"))[0].absoluteValue
        when {
            code.contentEquals("1".toByteArray()) -> {
                if (n > 0) {
                    System.out.write(adbSock.readInput(n.toInt(), "stdout blob"))
                    System.out.flush()
                }
            }
            code.contentEquals("2".toByteArray()) -> {
                if (n > 0) {
                    System.err.write(adbSock.readInput(n.toInt(), "stderr blob"))
                    System.err.flush()
                }
            }
            code.contentEquals("_".toByteArray()) -> {
                if (n > 0) {
                    val buff = ByteArray(n.toInt())
                    val read = System.`in`.read(buff, 0, n.toInt())
                    if (read == 0) {
                        adbSock.outStream.write("-".toByteArray() +  struct.pack("!i", -1))
                    } else {
                        adbSock.outStream.write("-".toByteArray() +  struct.pack("!i", read.toLong()) + buff)
                    }
                }
            }
            code.contentEquals("x".toByteArray()) -> {
                exitProcess(n.toInt())
            }
            else -> {
                throw IOException("Unexpected header: $code")
            }
        }
    }
}