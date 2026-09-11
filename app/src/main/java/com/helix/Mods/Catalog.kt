package com.helix

// contact blaku64th on discord if you have any issues ^^
object Catalog {
    val ExtraToggles: List<Utils.CustomToggleAction> = listOf(
        Utils.CustomToggleAction(
            "Mods Overlay", Utils.Category.MODS,
            onEnabled = { ctx ->
                ModMenu.start(AppContext.app)
                ctx.log("Mods Overlay service started press Left Home to open")
            },
            onDisabled = { ctx ->
                ModMenu.stop(AppContext.app)
                ctx.log("Mods Overlay stopped")
            }
        ),
        Utils.CustomToggleAction(
            "PSA Bypass", Utils.Category.MODS,
            onEnabled = { ctx -> PSA.start(ctx) },
            onDisabled = { ctx -> PSA.stop(ctx) }
        ),
        Utils.CustomToggleAction(
            "Joystick Fly", Utils.Category.MODS,
            onEnabled = { ctx ->
                Fly.joystickFlyEnabled = true
                Fly.start(ctx)
                ctx.log("Joystick Fly ON")
            },
            onDisabled = { ctx ->
                Fly.joystickFlyEnabled = false
                if (!Fly.aButtonFlyEnabled) Fly.stop(
                    ctx
                )
                else HeadlockHelper.disarm(ctx)
                ctx.log("Joystick Fly OFF")
            }
        ),
        Utils.CustomToggleAction(
            "A-Button Fly", Utils.Category.MODS,
            onEnabled = { ctx ->
                Fly.aButtonFlyEnabled = true
                Fly.start(ctx)
                ctx.log("A-Button Fly ON")
            },
            onDisabled = { ctx ->
                Fly.aButtonFlyEnabled = false
                if (!Fly.joystickFlyEnabled) Fly.stop(
                    ctx
                )
                else HeadlockHelper.disarm(ctx)
                ctx.log("A-Button Fly OFF")
            }
        ),
        Utils.CustomToggleAction(
            "Long Arms (Behind Head)", Utils.Category.MODS,
            onEnabled = { ctx ->
                LongArms.start(ctx)
                ctx.log("Long Arms ON")
                ctx.toast("Long Arms ON")
            },
            onDisabled = { ctx ->
                LongArms.stop(ctx)
                ctx.log("Long Arms OFF")
            }
        ),
        Utils.CustomToggleAction(
            "Long Arms (Break Hands)", Utils.Category.MODS,
            onEnabled = { ctx ->
                BreakHands.start(ctx)
                ctx.log("Break Hands ON — hold grip to break, release to fix")
            },
            onDisabled = { ctx ->
                BreakHands.stop(ctx)
                ctx.log("Break Hands OFF")
            }
        ),
        Utils.CustomToggleAction(
            "Wall Walk (grip pull)", Utils.Category.MODS,
            onEnabled = { ctx ->
                WallWalk.start(ctx)
                ctx.log("Wall Walk ON")
            },
            onDisabled = { ctx ->
                WallWalk.stop(ctx)
                HeadlockHelper.disarm(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Up/Down (RT up / LT down)", Utils.Category.MODS,
            onEnabled = { ctx ->
                UpDown.start(ctx)
                ctx.log("Up/Down ON")
            },
            onDisabled = { ctx ->
                UpDown.stop(ctx)
                HeadlockHelper.disarm(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Hover", Utils.Category.MODS,
            onEnabled = { ctx ->
                Hover.start(ctx)
                ctx.log("Hover ON")
            },
            onDisabled = { ctx ->
                Hover.stop(ctx)
                HeadlockHelper.disarm(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Velocity Fly (hold B)", Utils.Category.MODS,
            onEnabled = { ctx ->
                VelocityFly.start(ctx)
                ctx.log("Vel Fly ON")
            },
            onDisabled = { ctx ->
                VelocityFly.stop(ctx)
                HeadlockHelper.disarm(ctx)
            }
        ),

        Utils.CustomToggleAction(
            "Spaz", Utils.Category.MODS,
            onEnabled = { ctx -> Spaz.start(ctx) },
            onDisabled = { ctx -> Spaz.stop(ctx) }
        ),
        Utils.CustomToggleAction(
            "Grapple", Utils.Category.MODS,
            onEnabled = { ctx ->
                ctx.run("setprop debug.mod.grappleEnabled 1")
                Grapple.run(ctx)
                ctx.log("Grapple ON — hold grip + trigger")
                ctx.toast("Grapple: grip+trigger")
            },
            onDisabled = { ctx ->
                Grapple.stop()
                ctx.run("setprop debug.mod.grappleEnabled 0")
                runCatching { HeadlockHelper.disarm(ctx) }
                ctx.log("Grapple OFF")
            }
        ),
        Utils.CustomToggleAction(
            "Hover Fly", Utils.Category.MODS,
            onEnabled = { ctx ->
                MovementMods.set("hover", true, ctx)
                ctx.log("Hover ON hold RT, strafe with stick")
            },
            onDisabled = { ctx -> MovementMods.set("hover", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Glide", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("glide", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("glide", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Stick Strafe", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("strafe", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("strafe", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Auto Forward", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("autofwd", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("autofwd", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Slow Fall", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("slowfall", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("slowfall", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Air Brake", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("airbrake", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("airbrake", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Climb Assist", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("climbassist", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("climbassist", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Swim", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("swim", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("swim", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Zig Zag Run", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("zigzag", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("zigzag", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Orbit Spot", Utils.Category.MODS,
            onEnabled = { ctx ->
                MovementMods.set("orbit", true, ctx)
                ctx.log("Orbit ON")
            },
            onDisabled = { ctx -> MovementMods.set("orbit", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Bunny Hop", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("bunnyhop", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("bunnyhop", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Surf", Utils.Category.MODS,
            onEnabled = { ctx -> MovementMods.set("surf", true, ctx) },
            onDisabled = { ctx -> MovementMods.set("surf", false, ctx) }
        ),

        Utils.CustomToggleAction(
            "Platforms", Utils.Category.MODS,
            onEnabled = { ctx ->
                Platforms.start(ctx)
                ctx.log("Platforms ON")
            },
            onDisabled = { ctx -> Platforms.stop(ctx) }
        ),
        Utils.CustomToggleAction(
            "Low Gravity", Utils.Category.MODS,
            onEnabled = { ctx ->
                LowGravity.highGravity = false
                LowGravity.start(ctx)
                ctx.log("Low Gravity ON — rate=${LowGravity.effectiveLift}/s")
            },
            onDisabled = { ctx -> LowGravity.stop(ctx) }
        ),
        Utils.CustomToggleAction(
            "High Gravity", Utils.Category.MODS,
            onEnabled = { ctx ->
                LowGravity.highGravity = true
                if (!LowGravity.enabled) LowGravity.start(ctx)
                else {
                    ctx.log("High Gravity — rate=${LowGravity.effectiveLift}/s")
                    ctx.toast("High Gravity ON")
                }
            },
            onDisabled = { ctx ->
                LowGravity.highGravity = false
                ctx.toast("High Gravity OFF (still low if that toggle is on)")
            }
        ),
        Utils.CustomToggleAction(
            "Long Arms (IPD)", Utils.Category.MODS,
            onEnabled = { ctx ->
                IPDADB.longArms = true
                IPDADB.apply(ctx)
                Thread {
                    IPDADB.runHold(ctx)
                }.also { it.isDaemon = true; it.start() }
                ctx.log("Long Arms ON")
                ctx.toast("Long Arms ON")
            },
            onDisabled = { ctx ->
                IPDADB.longArms = false
                if (!IPDADB.isActive) IPDADB.reset(ctx)
                else IPDADB.apply(ctx)
                ctx.log("Long Arms OFF")
            }
        ),
        Utils.CustomToggleAction(
            "Tiny Body", Utils.Category.MODS,
            onEnabled = { ctx ->
                IPDADB.tiny = true
                IPDADB.apply(ctx)
                ctx.log("Tiny ON")
            },
            onDisabled = { ctx ->
                IPDADB.tiny = false
                if (!IPDADB.isActive) IPDADB.reset(ctx)
                else IPDADB.apply(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Giant Body", Utils.Category.MODS,
            onEnabled = { ctx ->
                IPDADB.giant = true
                IPDADB.apply(ctx)
            },
            onDisabled = { ctx ->
                IPDADB.giant = false
                if (!IPDADB.isActive) IPDADB.reset(ctx)
                else IPDADB.apply(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Taller", Utils.Category.MODS,
            onEnabled = { ctx ->
                IPDADB.tall = true
                IPDADB.apply(ctx)
            },
            onDisabled = { ctx ->
                IPDADB.tall = false
                if (!IPDADB.isActive) IPDADB.reset(ctx)
                else IPDADB.apply(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Shorter", Utils.Category.MODS,
            onEnabled = { ctx ->
                IPDADB.short = true
                IPDADB.apply(ctx)
            },
            onDisabled = { ctx ->
                IPDADB.short = false
                if (!IPDADB.isActive) IPDADB.reset(ctx)
                else IPDADB.apply(ctx)
            }
        ),
        Utils.CustomToggleAction(
            "Side Shift", Utils.Category.MODS,
            onEnabled = { ctx ->
                IPDADB.sideShift = true
                IPDADB.apply(ctx)
            },
            onDisabled = { ctx ->
                IPDADB.sideShift = false
                if (!IPDADB.isActive) IPDADB.reset(ctx)
                else IPDADB.apply(ctx)
            }
        ),

        Utils.CustomToggleAction(
            "Third Person Cam", Utils.Category.MODS,
            onEnabled = { ctx -> CameraMods.set("third", true, ctx) },
            onDisabled = { ctx -> CameraMods.set("third", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Over Shoulder Cam", Utils.Category.MODS,
            onEnabled = { ctx -> CameraMods.set("shoulder", true, ctx) },
            onDisabled = { ctx -> CameraMods.set("shoulder", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Top Down Cam", Utils.Category.MODS,
            onEnabled = { ctx -> CameraMods.set("topdown", true, ctx) },
            onDisabled = { ctx -> CameraMods.set("topdown", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Low Angle Cam", Utils.Category.MODS,
            onEnabled = { ctx -> CameraMods.set("lowangle", true, ctx) },
            onDisabled = { ctx -> CameraMods.set("lowangle", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Spectate Cam", Utils.Category.MODS,
            onEnabled = { ctx -> CameraMods.set("spectate", true, ctx) },
            onDisabled = { ctx -> CameraMods.set("spectate", false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Freeze", Utils.Category.MODS,
            onEnabled = { ctx -> CameraMods.set("freezebody", true, ctx) },
            onDisabled = { ctx -> CameraMods.set("freezebody", false, ctx) }
        ),

        Utils.CustomToggleAction(
            "Upside Down Head", Utils.Category.MODS,
            onEnabled = { ctx -> RotationMods.setUpsideDown(true, ctx) },
            onDisabled = { ctx -> RotationMods.setUpsideDown(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Backwards Head", Utils.Category.MODS,
            onEnabled = { ctx -> RotationMods.setBackwards(true, ctx) },
            onDisabled = { ctx -> RotationMods.setBackwards(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Spin Head", Utils.Category.MODS,
            onEnabled = { ctx -> RotationMods.setSpin(true, ctx) },
            onDisabled = { ctx -> RotationMods.setSpin(false, ctx) }
        ),

        Utils.CustomToggleAction(
            "Hold Both Grips", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldGrips(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldGrips(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Hold Both Triggers", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldTriggers(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldTriggers(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Hold A", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldA(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldA(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Hold X", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldX(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldX(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Hold A + X", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldAX(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldAX(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Hold B", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldB(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldB(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Hold Y", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setHoldY(true, ctx) },
            onDisabled = { ctx -> InputMods.setHoldY(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Finger Spaz", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setFingerSpaz(true, ctx) },
            onDisabled = { ctx -> InputMods.setFingerSpaz(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Grip Spaz", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setGripSpaz(true, ctx) },
            onDisabled = { ctx -> InputMods.setGripSpaz(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Mash Face Buttons", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setMashFace(true, ctx) },
            onDisabled = { ctx -> InputMods.setMashFace(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "No Finger Movement", Utils.Category.MODS,
            onEnabled = { ctx -> InputMods.setBlockKeyEvents(true, ctx) },
            onDisabled = { ctx -> InputMods.setBlockKeyEvents(false, ctx) }
        ),
        Utils.CustomToggleAction(
            "Stop All Input Mods", Utils.Category.MODS,
            onEnabled = { ctx ->
                InputMods.stopAll()
                ctx.log("All input mods stopped")
                ctx.toast("Input mods cleared")
            },
            onDisabled = { _ -> }
        ),
    )

    val ExtraButtons: List<Utils.CustomButtonAction> = listOf(
        Utils.CustomButtonAction("Dash", Utils.Category.MODS) { ctx ->
            MovementMods.dash(ctx)
        },
        Utils.CustomButtonAction("Rocket", Utils.Category.MODS) { ctx ->
            MovementMods.rocketLaunch(ctx)
        },
        Utils.CustomButtonAction("Save Checkpoint", Utils.Category.MODS) { ctx ->
            MovementMods.recallSave(ctx)
        },
        Utils.CustomButtonAction("Return To Checkpoint", Utils.Category.MODS) { ctx ->
            MovementMods.recallGo(ctx)
        },
        Utils.CustomButtonAction("Reset Pose", Utils.Category.MODS) { ctx ->
            MovementMods.resetPosition(ctx)
            Fly.resetPosition()
            HeadlockHelper.writeTranslation(ctx, 0f, 0f, 0f)
            ctx.log("Position reset")
            ctx.toast("Pose reset")
        },
        Utils.CustomButtonAction("Reset Scale", Utils.Category.MODS) { ctx ->
            IPDADB.reset(ctx)
            ctx.log("World scale reset")
            ctx.toast("Scale reset")
        },
        Utils.CustomButtonAction("Disarm Movement", Utils.Category.MODS) { ctx ->
            MovementMods.stop(ctx)
            CameraMods.stop(ctx)
            Fly.stop(ctx)
            LongArms.stop(ctx)
            BreakHands.stop(ctx)
            WallWalk.stop(ctx)
            UpDown.stop(ctx)
            Hover.stop(ctx)
            VelocityFly.stop(ctx)
            Platforms.stop(ctx)
            LowGravity.stop(ctx)
            RotationMods.stop(ctx)
            Spaz.stop(ctx)
            Grapple.stop()
            IPDADB.reset(ctx)
            HeadlockHelper.forceDisarm(ctx)
            ctx.log("All movement mods disarmed")
            ctx.toast("Disarmed")
        },
        Utils.CustomButtonAction("Find Inputs", Utils.Category.MODS) { ctx ->
            AdbButtonInput.relearnNodes()
            AdbButtonInput.release()
            AdbButtonInput.acquire()
            ctx.log(AdbButtonInput.capabilityHint())
            ctx.toast("Press grips / triggers")
        },
    )

    val EXTRA_SLIDERS: List<Utils.SliderAction> = listOf(
        Utils.SliderAction(
            "Fly Speed Tier", 0, 5, 2,
            "setprop debug.mod.flySpeed %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Fly Acceleration", 0, 3, 1,
            "setprop debug.mod.flyAccel %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Climb Rate", 0, 3, 1,
            "setprop debug.mod.climbRate %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Fly Smoothing", 0, 3, 2,
            "setprop debug.mod.flySmooth %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Dash Distance", 0, 5, 2,
            "setprop debug.mod.dashDist %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Blink Distance", 0, 4, 1,
            "setprop debug.mod.blinkDist %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Height", 0, 3, 1,
            "setprop debug.mod.heightAmount %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Camera Distance", 0, 3, 1,
            "setprop debug.mod.camDist %VALUE%", Utils.Category.MODS
        ),
        Utils.SliderAction(
            "Low Gravity Lift", 0, 10, 2,
            "setprop debug.mod.lowGravity %VALUE%", Utils.Category.MODS,
            step = 0.5f
        ),
        Utils.SliderAction(
            "Head Spin Speed", 15, 360, 90,
            "setprop debug.mod.headSpinSpeed %VALUE%", Utils.Category.MODS,
            step = 15f
        ),
    )

    fun mergedCustomToggles(): List<Utils.CustomToggleAction> {
        val existing = Utils.CustomToggles
        val extraLabels = ExtraToggles.map { it.label }.toSet()
        val kept = existing.filterNot {
            it.label.equals("Long Arms", ignoreCase = true) &&
                    extraLabels.any { e -> e.startsWith("Long Arms") }
        }
        return buildList {
            addAll(kept)
            addAll(ExtraToggles)
        }
    }

    fun mergedCustomButtons(): List<Utils.CustomButtonAction> = buildList {
        addAll(Utils.CustomButtons.filterIsInstance<Utils.CustomButtonAction>())
        addAll(ExtraButtons)
    }
    fun mergedSliders(): List<Utils.SliderAction> = buildList {
        addAll(Utils.Sliders)
        addAll(EXTRA_SLIDERS)
    }

    val ExtraCustomSliders: List<Utils.CustomSliderAction> = listOf(
        // Add more here, e.g.:
        // Utils.CustomSliderAction("My Slider", 0, 100, 50, Utils.Category.MODS) { ctx, v ->
        //     ctx.run("setprop debug.my.value ${'$'}{v.toInt()}")
        // }
    )

    fun mergedCustomSliders(): List<Utils.CustomSliderAction> = buildList {
        addAll(Utils.CustomSliders)
        addAll(ExtraCustomSliders)
    }
}