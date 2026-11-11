/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The server's enrollment key: one row, made once (see {@link com.hmdm.persistence.AgentEnrollmentKey}). */
public interface AgentEnrollmentKeyMapper {

    /** Stores the key unless one exists: of several first uses at once, exactly one key is kept. */
    @Insert({"INSERT INTO agentEnrollmentKey (id, keyHex, createdAt) VALUES (1, #{keyHex}, #{now}) " +
            "ON CONFLICT (id) DO NOTHING"})
    int insertIfAbsent(@Param("keyHex") String keyHex, @Param("now") long now);

    @Select({"SELECT keyHex FROM agentEnrollmentKey WHERE id = 1"})
    String get();
}
