package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.RemoteViewSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.UUID;
import java.util.List;

public interface RemoteViewSessionMapper {
    @Insert("INSERT INTO remoteViewSession (id,deviceNumber,requesterUserId,mode,state,agentJoinSecretHash,createdAt,consentExpiresAt,expiresAt) VALUES (#{id},#{deviceNumber},#{requesterUserId},#{mode},#{state},#{agentJoinSecretHash},#{createdAt},#{consentExpiresAt},#{expiresAt})")
    void insert(RemoteViewSession session);
    @Insert("INSERT INTO remoteViewSessionAudit (sessionId,state,at,actorUserId,detail) VALUES (#{sessionId},#{state},#{at},#{actorUserId},#{detail})")
    void insertAudit(@Param("sessionId") UUID sessionId, @Param("state") String state, @Param("at") long at,
                     @Param("actorUserId") Integer actorUserId, @Param("detail") String detail);
    @Select("SELECT * FROM remoteViewSession WHERE id=#{id}") RemoteViewSession find(@Param("id") UUID id);
    @Select("SELECT * FROM remoteViewSession WHERE endedAt IS NULL AND (consentExpiresAt < #{now} OR expiresAt < #{now})")
    List<RemoteViewSession> findExpired(@Param("now") long now);
    @Select("SELECT * FROM remoteViewSession WHERE deviceNumber=#{deviceNumber} AND endedAt IS NULL")
    List<RemoteViewSession> findActiveByDevice(@Param("deviceNumber") String deviceNumber);
    @Update("UPDATE remoteViewSession SET state=#{state}, endedAt=#{endedAt}, endReason=#{reason} WHERE id=#{id} AND endedAt IS NULL")
    int end(@Param("id") UUID id, @Param("state") String state, @Param("endedAt") long endedAt, @Param("reason") String reason);
}
