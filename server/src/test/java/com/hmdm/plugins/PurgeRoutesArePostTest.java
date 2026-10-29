/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.plugins;

import com.hmdm.plugins.messaging.rest.MessagingResource;
import com.hmdm.plugins.push.rest.PushResource;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Deleting message history changes state, so it is a POST (which the SameSite=Lax session cookie keeps same-site). */
public class PurgeRoutesArePostTest {

    @Test
    public void messagingPurgeIsAPost() throws Exception {
        assertPost(MessagingResource.class.getMethod("purgeMessages", Integer.class));
    }

    @Test
    public void pushPurgeIsAPost() throws Exception {
        assertPost(PushResource.class.getMethod("purgeMessages", Integer.class));
    }

    private static void assertPost(Method m) {
        assertTrue(m + " is a POST", m.isAnnotationPresent(POST.class));
        assertFalse(m + " is not a GET", m.isAnnotationPresent(GET.class));
    }
}
