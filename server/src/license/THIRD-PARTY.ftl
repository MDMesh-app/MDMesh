<#-- The server's META-INF/THIRD-PARTY.txt (license-maven-plugin, server/pom.xml): every third-party jar in the WAR's
     WEB-INF/lib, with the licence MDMesh uses it under (docs/licenses/policy.md). -->
<#function line licenses p>
    <#assign result = ""/>
    <#list licenses as license>
        <#assign result = result + "(" + license + ") "/>
    </#list>
    <#return result + p.name + " (" + p.groupId + ":" + p.artifactId + ":" + p.version + " - " + (p.url!"no url defined") + ")">
</#function>
The third-party components in the MDMesh server (WEB-INF/lib of launcher.war), and the licence MDMesh uses each
under. NOTICE has the source offer for the ones under a licence that asks for one.

<#list dependencyMap as e>
    ${line(e.getValue(), e.getKey())}
</#list>
<#-- The plugin can't read these two POMs on JDK 9 and later (their parent needs a tools.jar path, which only JDK 8
     has), so it leaves them out. .github/dependabot.yml holds com.sun.xml.bind at 2.2.11, and CI's check of the built
     WAR (scripts/licenses/inventory.py, in T0's server job) fails when the WAR's jars and this list differ. -->
    (CDDL-1.1) Old JAXB Core (com.sun.xml.bind:jaxb-core:2.2.11 - http://jaxb.java.net/)
    (CDDL-1.1) Old JAXB Runtime (com.sun.xml.bind:jaxb-impl:2.2.11 - http://jaxb.java.net/)
