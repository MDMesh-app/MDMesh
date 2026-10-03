# T2 native rig: a throwaway "fresh VPS" with systemd as PID 1, for tests/native/run.sh.
#
# MDMesh itself is NOT installed here: run.sh clones the repo into a container of this image and runs the documented
# native install (`sudo ./setup.sh --native -y`, DEPLOY.md Option C) exactly as a user would.
#
# The image holds three layers, each kept apart on purpose:
#   1. What a stock Debian/Ubuntu cloud image already has: systemd as init, sudo with a passwordless sudo user (the
#      cloud-init default user), procps, iproute2, ca-certificates and openssl.
#   2. The prerequisites DEPLOY.md documents for a native install: git (to clone). Nothing else; the installer installs
#      its own packages (JDK, Postgres, Maven, Node, npm, curl, python3, aapt, minisign).
#   3. WORKAROUNDS: anything the installer needs that the docs do not list. Each one is a finding (docs/DEV.md, "T2 native
#      rig") and is added only when run.sh passes its build arg, never by default; T2_STRICT=1 builds without any of them.
#
# Units that would act on the HOST from a privileged container (sysctl, kernel modules, binfmt, pstore, clocks, TRIM)
# are masked: the container only has to look like a VPS to the installer, never touch the machine it runs on.
ARG BASE_IMAGE=debian:12
FROM ${BASE_IMAGE}

ENV container=docker \
    DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8

# 1. Stock cloud image baseline + 2. documented prerequisites (git).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      systemd systemd-sysv dbus sudo procps iproute2 ca-certificates openssl \
      git \
 && rm -rf /var/lib/apt/lists/*

# Undo the container-image tweaks a VPS does not have. policy-rc.d (exit 101) stops every package from starting its
# service, so `apt-get install postgresql` would leave no running cluster; the dpkg/apt snippets drop docs, locales and
# the package cache.
RUN rm -f /usr/sbin/policy-rc.d /etc/apt/apt.conf.d/docker-* /etc/dpkg/dpkg.cfg.d/docker-* /etc/dpkg/dpkg.cfg.d/excludes

# Never let the guest act on the host kernel or hardware; also no consoles and no background apt runs (a VPS has
# apt-daily, but its dpkg lock would make the rig flaky, and it is not what the rig tests). Masking a unit that is not
# installed is harmless (a /dev/null link).
RUN systemctl mask \
      systemd-sysctl.service systemd-modules-load.service systemd-binfmt.service \
      proc-sys-fs-binfmt_misc.automount proc-sys-fs-binfmt_misc.mount \
      sys-kernel-config.mount sys-kernel-debug.mount sys-kernel-tracing.mount sys-fs-fuse-connections.mount \
      systemd-udevd.service systemd-udev-trigger.service systemd-udev-settle.service systemd-hwdb-update.service \
      systemd-pstore.service systemd-random-seed.service systemd-timesyncd.service systemd-remount-fs.service \
      systemd-firstboot.service kmod-static-nodes.service \
      fstrim.timer fstrim.service e2scrub_all.timer e2scrub_reap.service \
      apt-daily.timer apt-daily-upgrade.timer \
      getty.target console-getty.service serial-getty@.service \
 && systemctl set-default multi-user.target

# The cloud-init style default user: the one who clones the repo and runs `sudo ./setup.sh --native`.
RUN useradd -m -s /bin/bash t2user \
 && printf 't2user ALL=(ALL) NOPASSWD:ALL\n' > /etc/sudoers.d/90-t2user \
 && chmod 440 /etc/sudoers.d/90-t2user

# 3. WORKAROUNDS (empty build args = none). Every download is pinned and checked against its published SHA-256.
# T2_NODE_URL/T2_NODE_SHA256: a Node.js release tarball unpacked into /usr/local (system-wide, so the mdmesh service user
#   can run it), for distros whose packaged Node is older than the web build (Vite) needs.
# T2_JDK17_URL/T2_JDK17_SHA256: a JDK 17 tarball unpacked under /opt (the installer looks for /opt/*jdk-17*), for distros
#   that package no openjdk-17-jdk.
ARG T2_NODE_URL=""
ARG T2_NODE_SHA256=""
ARG T2_JDK17_URL=""
ARG T2_JDK17_SHA256=""
RUN set -eu; \
    fetch() { url=$1 sum=$2 out=$3; \
      apt-get update >/dev/null; apt-get install -y --no-install-recommends curl xz-utils >/dev/null; \
      curl -fsSL --retry 3 -o "$out" "$url"; \
      echo "$sum  $out" | sha256sum -c -; }; \
    if [ -n "$T2_NODE_URL" ]; then \
      fetch "$T2_NODE_URL" "$T2_NODE_SHA256" /tmp/node.tar.xz; \
      tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --no-same-owner \
        --exclude='*/CHANGELOG.md' --exclude='*/LICENSE' --exclude='*/README.md'; \
      rm -f /tmp/node.tar.xz; node --version; \
    fi; \
    if [ -n "$T2_JDK17_URL" ]; then \
      fetch "$T2_JDK17_URL" "$T2_JDK17_SHA256" /tmp/jdk.tar.gz; \
      mkdir -p /opt/jdk-17; tar -xzf /tmp/jdk.tar.gz -C /opt/jdk-17 --strip-components=1 --no-same-owner; \
      rm -f /tmp/jdk.tar.gz; /opt/jdk-17/bin/javac -version; \
    fi; \
    if [ -n "$T2_NODE_URL$T2_JDK17_URL" ]; then \
      apt-get purge -y --auto-remove curl xz-utils >/dev/null; rm -rf /var/lib/apt/lists/*; \
    fi

STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
