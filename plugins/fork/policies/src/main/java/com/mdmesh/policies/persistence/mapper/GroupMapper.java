package com.mdmesh.policies.persistence.mapper;

import com.mdmesh.policies.persistence.domain.BrandRow;
import com.mdmesh.policies.persistence.domain.GroupLink;
import com.mdmesh.policies.persistence.domain.GroupRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * Device groups and brands. Every query is scoped to one customer. Phones are read through core's devices table, so a
 * deleted or wiped phone drops out of every list by itself, and a group whose policy was deleted shows no policy.
 */
public interface GroupMapper {

    // ---- reading ----

    @Select({"SELECT g.id AS id, g.customerId AS customerId, g.name AS name, c.id AS configurationId, g.createdAt AS createdAt",
            "FROM plugin_policies_group g",
            "LEFT JOIN configurations c ON c.id = g.configurationId AND c.customerId = g.customerId",
            "WHERE g.customerId = #{customerId} ORDER BY lower(g.name), g.id"})
    List<GroupRow> groups(@Param("customerId") int customerId);

    @Select({"SELECT g.id AS id, g.customerId AS customerId, g.name AS name, c.id AS configurationId, g.createdAt AS createdAt",
            "FROM plugin_policies_group g",
            "LEFT JOIN configurations c ON c.id = g.configurationId AND c.customerId = g.customerId",
            "WHERE g.customerId = #{customerId} AND g.id = #{id}"})
    GroupRow group(@Param("customerId") int customerId, @Param("id") int id);

    @Select({"SELECT groupId, brand FROM plugin_policies_group_brand WHERE customerId = #{customerId} ORDER BY brand"})
    List<GroupLink> groupBrands(@Param("customerId") int customerId);

    @Select({"SELECT gd.groupId AS groupId, gd.deviceId AS deviceId FROM plugin_policies_group_device gd",
            "INNER JOIN devices d ON d.id = gd.deviceId AND d.customerId = gd.customerId AND d.wipedAt IS NULL",
            "WHERE gd.customerId = #{customerId} ORDER BY gd.deviceId"})
    List<GroupLink> groupDevices(@Param("customerId") int customerId);

    @Select({"SELECT brand, label FROM plugin_policies_brand WHERE customerId = #{customerId} ORDER BY lower(label)"})
    List<BrandRow> brands(@Param("customerId") int customerId);

    @Select({"SELECT db.deviceId AS deviceId, db.brand AS brand FROM plugin_policies_device_brand db",
            "INNER JOIN devices d ON d.id = db.deviceId AND d.customerId = db.customerId AND d.wipedAt IS NULL",
            "WHERE db.customerId = #{customerId} ORDER BY db.deviceId"})
    List<BrandRow> deviceBrands(@Param("customerId") int customerId);

    /** Of these device ids, the ones that are the customer's phones (not wiped). */
    @Select({"<script>",
            "SELECT id FROM devices WHERE customerId = #{customerId} AND wipedAt IS NULL AND id IN",
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    List<Integer> existingDevices(@Param("customerId") int customerId, @Param("ids") Collection<Integer> ids);

    @Select({"SELECT COUNT(*) FROM configurations WHERE id = #{id} AND customerId = #{customerId}"})
    int configurationExists(@Param("customerId") int customerId, @Param("id") int id);

    /** Groups with this name (any case), other than {@code exceptId}. */
    @Select({"SELECT COUNT(*) FROM plugin_policies_group WHERE customerId = #{customerId}",
            "AND lower(name) = lower(#{name}) AND id <> #{exceptId}"})
    int countNamed(@Param("customerId") int customerId, @Param("name") String name, @Param("exceptId") int exceptId);

    @Select({"SELECT (SELECT COUNT(*) FROM plugin_policies_device_brand db",
            "        INNER JOIN devices d ON d.id = db.deviceId AND d.customerId = db.customerId AND d.wipedAt IS NULL",
            "        WHERE db.customerId = #{customerId} AND db.brand = #{brand})",
            "     + (SELECT COUNT(*) FROM plugin_policies_group_brand WHERE customerId = #{customerId} AND brand = #{brand})"})
    int brandUses(@Param("customerId") int customerId, @Param("brand") String brand);

    // ---- groups ----

    @Insert({"INSERT INTO plugin_policies_group (customerId, name, configurationId, createdAt)",
            "VALUES (#{customerId}, #{name}, #{configurationId}, #{createdAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertGroup(GroupRow g);

    @Update({"UPDATE plugin_policies_group SET name = #{name}, configurationId = #{configurationId}",
            "WHERE id = #{id} AND customerId = #{customerId}"})
    int updateGroup(GroupRow g);

    /** Its brands and phones go with it (ON DELETE CASCADE). */
    @Delete({"DELETE FROM plugin_policies_group WHERE id = #{id} AND customerId = #{customerId}"})
    int deleteGroup(@Param("customerId") int customerId, @Param("id") int id);

    @Update({"<script>",
            "UPDATE plugin_policies_group SET configurationId = #{configurationId}",
            "WHERE customerId = #{customerId} AND id IN",
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    int setPolicy(@Param("customerId") int customerId, @Param("configurationId") Integer configurationId,
                  @Param("ids") Collection<Integer> ids);

    // ---- a group's brands ----

    @Delete({"DELETE FROM plugin_policies_group_brand WHERE groupId = #{groupId}"})
    void clearBrands(@Param("groupId") int groupId);

    /** Takes these brands out of whatever group has them. */
    @Delete({"<script>",
            "DELETE FROM plugin_policies_group_brand WHERE customerId = #{customerId} AND brand IN",
            "<foreach item='b' collection='brands' open='(' separator=',' close=')'>#{b}</foreach>",
            "</script>"})
    void releaseBrands(@Param("customerId") int customerId, @Param("brands") Collection<String> brands);

    @Insert({"<script>",
            "INSERT INTO plugin_policies_group_brand (customerId, brand, groupId) VALUES",
            "<foreach item='b' collection='brands' separator=','>(#{customerId}, #{b}, #{groupId})</foreach>",
            "</script>"})
    void insertBrands(@Param("customerId") int customerId, @Param("groupId") int groupId, @Param("brands") Collection<String> brands);

    // ---- phones picked into a group ----

    @Delete({"DELETE FROM plugin_policies_group_device WHERE groupId = #{groupId}"})
    void clearDevices(@Param("groupId") int groupId);

    /** Takes these phones out of whatever group they were picked into. */
    @Delete({"<script>",
            "DELETE FROM plugin_policies_group_device WHERE customerId = #{customerId} AND deviceId IN",
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    void releaseDevices(@Param("customerId") int customerId, @Param("ids") Collection<Integer> ids);

    @Insert({"<script>",
            "INSERT INTO plugin_policies_group_device (deviceId, customerId, groupId) VALUES",
            "<foreach item='i' collection='ids' separator=','>(#{i}, #{customerId}, #{groupId})</foreach>",
            "</script>"})
    void insertDevices(@Param("customerId") int customerId, @Param("groupId") int groupId, @Param("ids") Collection<Integer> ids);

    // ---- brands ----

    @Insert({"INSERT INTO plugin_policies_brand (customerId, brand, label) VALUES (#{customerId}, #{brand}, #{label})",
            "ON CONFLICT (customerId, brand) DO UPDATE SET label = EXCLUDED.label"})
    void saveBrand(@Param("customerId") int customerId, @Param("brand") String brand, @Param("label") String label);

    @Delete({"DELETE FROM plugin_policies_brand WHERE customerId = #{customerId} AND brand = #{brand}"})
    int deleteBrand(@Param("customerId") int customerId, @Param("brand") String brand);

    /** Phones lose the brand set on them by hand, and show the brand they report again. */
    @Delete({"<script>",
            "DELETE FROM plugin_policies_device_brand WHERE customerId = #{customerId} AND deviceId IN",
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    void clearDeviceBrands(@Param("customerId") int customerId, @Param("ids") Collection<Integer> ids);

    @Insert({"<script>",
            "INSERT INTO plugin_policies_device_brand (deviceId, customerId, brand) VALUES",
            "<foreach item='i' collection='ids' separator=','>(#{i}, #{customerId}, #{brand})</foreach>",
            "</script>"})
    void insertDeviceBrands(@Param("customerId") int customerId, @Param("brand") String brand, @Param("ids") Collection<Integer> ids);
}
