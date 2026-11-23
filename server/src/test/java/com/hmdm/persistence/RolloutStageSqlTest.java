package com.hmdm.persistence;

import com.hmdm.persistence.mapper.RolloutMapper;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Stage changes are compare-and-set, so concurrent promote, cancel and canary completion can't overwrite each other. */
public class RolloutStageSqlTest {

    @Test
    public void aStageMoveOnlyHappensFromTheStageThatWasRead() {
        String where = Fakes.whereOf(Fakes.sqlOf(RolloutMapper.class, "moveStage"));
        assertTrue(where, where.contains("where id = #{id} and stage = #{from}"));
    }

    @Test
    public void cancelOnlyStopsARunningRollout() {
        String where = Fakes.whereOf(Fakes.sqlOf(RolloutMapper.class, "cancelActive"));
        assertTrue(where, where.contains("where id = #{id} and stage in ('canary','fleet')"));
    }
}
