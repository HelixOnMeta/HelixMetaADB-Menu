API ?= 35
PROJECT ?= eureka-52168470043600520
OUTDIR ?= build/$(PROJECT)/bin
EMBEDDIR ?= build/embed

TARGET_DIR := src/targets/$(PROJECT)
TARGET_HEADER := $(TARGET_DIR)/target.h

ifeq ($(wildcard $(TARGET_HEADER)),)
$(error unknown PROJECT=$(PROJECT), missing $(TARGET_HEADER))
endif

# define pick_src
# $(if $(wildcard $(TARGET_DIR)/$(1)),$(TARGET_DIR)/$(1),src/$(1))
# endef
define pick_src
src/$(1)
endef
EMBED_SU := $(EMBEDDIR)/su_daemon_aarch64_pie
EMBED_EXP32 := $(EMBEDDIR)/cve_test_arm32_pie
PRELOAD := $(OUTDIR)/preload
WALLPAPER := assets/wallpaper.webp

CORE_SRCS := \
  $(call pick_src,main.c) \
  $(call pick_src,util.c) \
  $(call pick_src,slide.c) \
  $(call pick_src,api.c) \
  $(call pick_src,config.c) \
  $(call pick_src,fops.c) \
  $(call pick_src,pipe.c) \
  $(call pick_src,q3slide.c) \
  src/root.c
PRELOAD_SRCS := $(CORE_SRCS) src/preload.c src/su_blob.S src/wallpaper_blob.S src/exp32_blob.S

.DEFAULT_GOAL := preload

DEFAULT_NDK_ROOT := $(HOME)/android-ndk-cache/android-ndk-r29
NDK_ROOT ?= $(or $(ANDROID_NDK_HOME),$(ANDROID_NDK_ROOT),$(wildcard $(DEFAULT_NDK_ROOT)))
NDK_TOOLCHAIN ?= $(if $(NDK_ROOT),$(NDK_ROOT)/toolchains/llvm/prebuilt/linux-x86_64)
NDK_CC := $(NDK_TOOLCHAIN)/bin/aarch64-linux-android$(API)-clang
# 32-bit toolchain for the embedded armeabi-v7a exploit stage
API32 ?= 28
NDK_CC32 := $(NDK_TOOLCHAIN)/bin/armv7a-linux-androideabi$(API32)-clang
HOST_CLANG ?= clang
SYSROOT ?= $(if $(NDK_TOOLCHAIN),$(NDK_TOOLCHAIN)/sysroot)
RESOURCE_DIR ?= $(if $(NDK_TOOLCHAIN),$(NDK_TOOLCHAIN)/lib/clang/21)

HOST_TARGET_FLAGS := \
  --target=aarch64-linux-android$(API) \
  --sysroot=$(SYSROOT) \
  -resource-dir $(RESOURCE_DIR) \
  --rtlib=compiler-rt \
  --unwindlib=none
HOST_COMMON_LDFLAGS := \
  -fuse-ld=lld \
  -Wl,-rpath-link,$(SYSROOT)/usr/lib/aarch64-linux-android/$(API) \
  -L$(SYSROOT)/usr/lib/aarch64-linux-android/$(API) \
  -L$(SYSROOT)/usr/lib/aarch64-linux-android
HOST_PIE_LDFLAGS := \
  $(HOST_COMMON_LDFLAGS) \
  -Wl,-dynamic-linker,/system/bin/linker64

HOST32_TARGET_FLAGS := \
  --target=armv7a-linux-androideabi$(API32) \
  --sysroot=$(SYSROOT) \
  -resource-dir $(RESOURCE_DIR) \
  --rtlib=compiler-rt \
  --unwindlib=none \
  -march=armv7-a \
  -mfloat-abi=softfp
HOST32_COMMON_LDFLAGS := \
  -fuse-ld=lld \
  -Wl,-rpath-link,$(SYSROOT)/usr/lib/arm-linux-androideabi/$(API32) \
  -L$(SYSROOT)/usr/lib/arm-linux-androideabi/$(API32) \
  -L$(SYSROOT)/usr/lib/arm-linux-androideabi
HOST32_PIE_LDFLAGS := \
  $(HOST32_COMMON_LDFLAGS) \
  -Wl,-dynamic-linker,/system/bin/linker

ifneq ($(origin CC),default)
  TARGET_CC := $(CC)
  TARGET_FLAGS :=
  TARGET_COMMON_LDFLAGS :=
  TARGET_PIE_LDFLAGS :=
else ifneq ($(wildcard $(NDK_CC)),)
  NDK_CC_WORKS := $(shell $(NDK_CC) --version >/dev/null 2>&1 && echo yes)
  ifeq ($(NDK_CC_WORKS),yes)
    TARGET_CC := $(NDK_CC)
    TARGET_FLAGS :=
    TARGET_COMMON_LDFLAGS :=
    TARGET_PIE_LDFLAGS :=
  else
    TARGET_CC := $(HOST_CLANG)
    TARGET_FLAGS := $(HOST_TARGET_FLAGS)
    TARGET_COMMON_LDFLAGS := $(HOST_COMMON_LDFLAGS)
    TARGET_PIE_LDFLAGS := $(HOST_PIE_LDFLAGS)
  endif
else
  TARGET_CC := $(HOST_CLANG)
  TARGET_FLAGS := $(HOST_TARGET_FLAGS)
  TARGET_COMMON_LDFLAGS := $(HOST_COMMON_LDFLAGS)
  TARGET_PIE_LDFLAGS := $(HOST_PIE_LDFLAGS)
endif

# 32-bit compiler selection mirrors the 64-bit one above.
ifneq ($(wildcard $(NDK_CC32)),)
  NDK_CC32_WORKS := $(shell $(NDK_CC32) --version >/dev/null 2>&1 && echo yes)
  ifeq ($(NDK_CC32_WORKS),yes)
    TARGET_CC32 := $(NDK_CC32)
    TARGET32_FLAGS :=
    TARGET32_PIE_LDFLAGS :=
  else
    TARGET_CC32 := $(HOST_CLANG)
    TARGET32_FLAGS := $(HOST32_TARGET_FLAGS)
    TARGET32_PIE_LDFLAGS := $(HOST32_PIE_LDFLAGS)
  endif
else
  TARGET_CC32 := $(HOST_CLANG)
  TARGET32_FLAGS := $(HOST32_TARGET_FLAGS)
  TARGET32_PIE_LDFLAGS := $(HOST32_PIE_LDFLAGS)
endif

DEBUG ?= 0
DEBUG_CFLAGS := $(if $(filter 1,$(DEBUG)),-DDEBUG)

COMMON_CFLAGS := -O2 -g0 -Wall -Wextra -Isrc $(DEBUG_CFLAGS)
PIE_CFLAGS := -fPIE -pie $(COMMON_CFLAGS)
SO_CFLAGS := -fPIC $(COMMON_CFLAGS)
WARN_CFLAGS := -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function
TARGET_CFLAGS := -DTARGET_CONFIG_H=\"targets/$(PROJECT)/target.h\"

.PHONY: all preload clean info list-projects

all: preload

preload: $(PRELOAD)

$(OUTDIR):
	mkdir -p $@

$(EMBEDDIR):
	mkdir -p $@

$(EMBED_SU): src/su_daemon.c | $(EMBEDDIR)
	$(TARGET_CC) $(TARGET_FLAGS) $(PIE_CFLAGS) $(TARGET_CFLAGS) \
	  $< $(TARGET_PIE_LDFLAGS) -o $@

# armeabi-v7a static PIE — MUST stay 32-bit (compat syscall path).
EXP32_SRCS := src/exp32/main.c src/exp32/stack.c

$(EMBED_EXP32): $(EXP32_SRCS) src/kernelsnitch/utils.h | $(EMBEDDIR)
	$(TARGET_CC32) $(TARGET32_FLAGS) -O2 -g0 -Wall -Isrc $(DEBUG_CFLAGS) \
	  -Wno-unused-parameter -Wno-unused-function \
	  -fPIE -pie -static $(EXP32_SRCS) $(TARGET32_PIE_LDFLAGS) -o $@

$(PRELOAD): $(PRELOAD_SRCS) $(EMBED_SU) $(EMBED_EXP32) $(WALLPAPER) $(TARGET_HEADER) src/offset.h src/common.h src/config.h src/kernelsnitch/*.h | $(OUTDIR)
	$(TARGET_CC) $(TARGET_FLAGS) $(SO_CFLAGS) $(WARN_CFLAGS) $(TARGET_CFLAGS) \
	  $(PRELOAD_SRCS) $(TARGET_COMMON_LDFLAGS) -static\
	  -o $@ -pthread
	sha256sum $@

info:
	@echo "PROJECT=$(PROJECT)"
	@echo "TARGET_DIR=$(TARGET_DIR)"
	@echo "TARGET_CC=$(TARGET_CC)"
	@echo "TARGET_FLAGS=$(TARGET_FLAGS)"
	@echo "TARGET_COMMON_LDFLAGS=$(TARGET_COMMON_LDFLAGS)"
	@echo "TARGET_PIE_LDFLAGS=$(TARGET_PIE_LDFLAGS)"
	@echo "TARGET_CC32=$(TARGET_CC32)"
	@echo "TARGET32_FLAGS=$(TARGET32_FLAGS)"
	@echo "TARGET32_PIE_LDFLAGS=$(TARGET32_PIE_LDFLAGS)"
	@echo "PRELOAD=$(PRELOAD)"
	@echo "EMBED_SU=$(EMBED_SU)"
	@echo "EMBED_EXP32=$(EMBED_EXP32)"
	@echo "WALLPAPER=$(WALLPAPER)"
	@echo "CORE_SRCS=$(CORE_SRCS)"

list-projects:
	@find src/targets -mindepth 2 -maxdepth 2 -name target.h -printf '%h\n' | sed 's#src/targets/##' | sort

clean:
	rm -rf build
