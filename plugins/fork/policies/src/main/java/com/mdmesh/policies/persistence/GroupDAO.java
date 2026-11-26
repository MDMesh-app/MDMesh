package com.mdmesh.policies.persistence;

import com.mdmesh.policies.persistence.domain.GroupRow;
import com.mdmesh.policies.persistence.mapper.GroupMapper;
import org.mybatis.guice.transactional.Transactional;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Group changes in the database, each all or nothing, so a brand or phone is never left in two groups or none by half. */
@Singleton
public class GroupDAO {
    /** Rows per multi-row statement. */
    static final int CHUNK = 500;

    private final GroupMapper mapper;

    @Inject
    public GroupDAO(GroupMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Creates (no id) or updates the group, with exactly these brands and picked phones: they leave any other group
     * first. Returns the group's id, or null when the group to update doesn't exist.
     */
    @Transactional
    public Integer save(GroupRow g, Collection<String> brands, Collection<Integer> deviceIds) {
        if (g.getId() == null) {
            mapper.insertGroup(g);
        } else if (mapper.updateGroup(g) == 0) {
            return null;
        }
        int id = g.getId();
        mapper.clearBrands(id);
        mapper.clearDevices(id);
        for (List<String> part : chunks(brands)) {
            mapper.releaseBrands(g.getCustomerId(), part);
            mapper.insertBrands(g.getCustomerId(), id, part);
        }
        for (List<Integer> part : chunks(deviceIds)) {
            mapper.releaseDevices(g.getCustomerId(), part);
            mapper.insertDevices(g.getCustomerId(), id, part);
        }
        return id;
    }

    /** Picks these phones into group {@code groupId}, or with null takes them out of any group they were picked into. */
    @Transactional
    public void setMembers(int customerId, Collection<Integer> deviceIds, Integer groupId) {
        for (List<Integer> part : chunks(deviceIds)) {
            mapper.releaseDevices(customerId, part);
            if (groupId != null) mapper.insertDevices(customerId, groupId, part);
        }
    }

    /** Sets this brand on these phones by hand, or with null lets them show the brand they report again. */
    @Transactional
    public void setDeviceBrands(int customerId, Collection<Integer> deviceIds, String brand) {
        for (List<Integer> part : chunks(deviceIds)) {
            mapper.clearDeviceBrands(customerId, part);
            if (brand != null) mapper.insertDeviceBrands(customerId, brand, part);
        }
    }

    /** Splits a big list for multi-row statements and IN (...) lists. */
    public static <T> List<List<T>> chunks(Collection<T> all) {
        List<T> list = new ArrayList<>(all);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK) out.add(list.subList(i, Math.min(list.size(), i + CHUNK)));
        return out;
    }
}
