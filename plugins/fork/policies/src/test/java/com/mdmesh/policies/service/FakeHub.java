package com.mdmesh.policies.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stands in for core's AgentWakeHub: the INSTANCE field and the two methods CoreWake uses. */
public class FakeHub {
    public static volatile FakeHub INSTANCE;

    public final Set<String> online = new HashSet<>();
    public final List<String> woken = new ArrayList<>();

    public boolean isOnline(String number) {
        return online.contains(number);
    }

    public void wake(String number, String kind) {
        woken.add(number + ":" + kind);
    }
}
