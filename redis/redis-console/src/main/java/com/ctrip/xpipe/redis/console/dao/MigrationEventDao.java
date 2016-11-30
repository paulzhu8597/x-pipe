package com.ctrip.xpipe.redis.console.dao;

import java.util.LinkedList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.unidal.dal.jdbc.DalException;
import org.unidal.helper.Lists;
import org.unidal.lookup.ContainerLoader;

import com.ctrip.xpipe.api.sso.UserInfoHolder;
import com.ctrip.xpipe.redis.console.annotation.DalTransaction;
import com.ctrip.xpipe.redis.console.exception.BadRequestException;
import com.ctrip.xpipe.redis.console.exception.ServerException;
import com.ctrip.xpipe.redis.console.migration.status.cluster.ClusterStatus;
import com.ctrip.xpipe.redis.console.migration.status.migration.MigraionStatus;
import com.ctrip.xpipe.redis.console.model.ClusterTbl;
import com.ctrip.xpipe.redis.console.model.ClusterTblDao;
import com.ctrip.xpipe.redis.console.model.ClusterTblEntity;
import com.ctrip.xpipe.redis.console.model.MigrationClusterTbl;
import com.ctrip.xpipe.redis.console.model.MigrationClusterTblDao;
import com.ctrip.xpipe.redis.console.model.MigrationClusterTblEntity;
import com.ctrip.xpipe.redis.console.model.MigrationEventModel;
import com.ctrip.xpipe.redis.console.model.MigrationEventTbl;
import com.ctrip.xpipe.redis.console.model.MigrationEventTblDao;
import com.ctrip.xpipe.redis.console.model.MigrationEventTblEntity;
import com.ctrip.xpipe.redis.console.model.MigrationShardTbl;
import com.ctrip.xpipe.redis.console.model.MigrationShardTblDao;
import com.ctrip.xpipe.redis.console.model.ShardTbl;
import com.ctrip.xpipe.redis.console.model.ShardTblDao;
import com.ctrip.xpipe.redis.console.model.ShardTblEntity;
import com.ctrip.xpipe.redis.console.query.DalQuery;
import com.ctrip.xpipe.redis.console.util.DataModifiedTimeGenerator;

@Repository
public class MigrationEventDao extends AbstractXpipeConsoleDAO {
	@Autowired
	private UserInfoHolder userInfo;

	private MigrationEventTblDao migrationEventDao;
	private MigrationClusterTblDao migrationClusterDao;
	private MigrationShardTblDao migrationShardDao;
	private ClusterTblDao clusterTblDao;
	private ShardTblDao shardTblDao;

	@PostConstruct
	private void postConstruct() {
		try {
			migrationEventDao = ContainerLoader.getDefaultContainer().lookup(MigrationEventTblDao.class);
			migrationClusterDao = ContainerLoader.getDefaultContainer().lookup(MigrationClusterTblDao.class);
			migrationShardDao = ContainerLoader.getDefaultContainer().lookup(MigrationShardTblDao.class);
			clusterTblDao = ContainerLoader.getDefaultContainer().lookup(ClusterTblDao.class);
			shardTblDao = ContainerLoader.getDefaultContainer().lookup(ShardTblDao.class);
		} catch (ComponentLookupException e) {
			throw new ServerException("Cannot construct dao.", e);
		}
	}

	@DalTransaction
	public long createMigrationEvnet(MigrationEventModel event) {
		if (null != event) {
			/** Create event **/
			MigrationEventTbl proto = migrationEventDao.createLocal();
			final String eventTag = generateUniqueEventTag(userInfo.getUser().getUserId());
			proto.setOperator(userInfo.getUser().getUserId()).setEventTag(eventTag);
			final MigrationEventTbl forCreate = proto;
			final MigrationEventTbl result = queryHandler.handleQuery(new DalQuery<MigrationEventTbl>() {
				@Override
				public MigrationEventTbl doQuery() throws DalException {
					migrationEventDao.insert(forCreate);
					return migrationEventDao.findByTag(eventTag, MigrationEventTblEntity.READSET_FULL);
				}
			});

			/** Create migration clusters task **/
			final List<MigrationClusterTbl> migrationClusters = createMigrationClusters(result.getId(),
					event.getEvent().getMigrationClusters());

			/** Create migration shards task **/
			createMigrationShards(migrationClusters);

			return result.getId();
		} else {
			throw new BadRequestException("Cannot create migration event from nothing!");
		}
	}

	private List<MigrationClusterTbl> createMigrationClusters(final long eventId, List<MigrationClusterTbl> migrationClusters) {
		final List<MigrationClusterTbl> toCreateMigrationCluster = new LinkedList<>();

		if (null != migrationClusters) {
			for (MigrationClusterTbl migrationCluster : migrationClusters) {
				updateClusterStatus(migrationCluster.getClusterId(), ClusterStatus.Lock);
				
				MigrationClusterTbl proto = migrationClusterDao.createLocal();
				proto.setMigrationEventId(eventId).setClusterId(migrationCluster.getClusterId())
						.setDestinationDcId(migrationCluster.getDestinationDcId()).setStatus(MigraionStatus.Initiated.toString());
				toCreateMigrationCluster.add(proto);
			}
		}

		return queryHandler.handleQuery(new DalQuery<List<MigrationClusterTbl>>() {
			@Override
			public List<MigrationClusterTbl> doQuery() throws DalException {
				migrationClusterDao.insertBatch(Lists.toArray(MigrationClusterTbl.class, toCreateMigrationCluster));
				return migrationClusterDao.findByEventId(eventId, MigrationClusterTblEntity.READSET_FULL);
			}
		});
	}
	
	private void updateClusterStatus(final long clusterId, ClusterStatus status) {
		ClusterTbl cluster = queryHandler.handleQuery(new DalQuery<ClusterTbl>() {
			@Override
			public ClusterTbl doQuery() throws DalException {
				return clusterTblDao.findByPK(clusterId, ClusterTblEntity.READSET_FULL);
			}
		});
		if(null == cluster) throw new BadRequestException(String.format("Cluster:%s do not exist!", clusterId));
		
		if(!cluster.getStatus().toLowerCase().equals(ClusterStatus.Normal.toString().toLowerCase())) {
			throw new BadRequestException(String.format("Cluster:%s already under migrating tasks!Please verify it first!", cluster.getClusterName()));
		} else {
			cluster.setStatus(ClusterStatus.Lock.toString());
		}
		
		final ClusterTbl proto = cluster;
		queryHandler.handleQuery(new DalQuery<Void>() {
			@Override
			public Void doQuery() throws DalException {
				clusterTblDao.updateByPK(proto, ClusterTblEntity.UPDATESET_FULL);
				return null;
			}
			
		});
	}

	private void createMigrationShards(List<MigrationClusterTbl> migrationClusters) {
		final List<MigrationShardTbl> toCreateMigrationShards = new LinkedList<>();

		if (null != migrationClusters) {
			for (final MigrationClusterTbl migrationCluster : migrationClusters) {
				List<ShardTbl> shards = queryHandler.handleQuery(new DalQuery<List<ShardTbl>>() {
					@Override
					public List<ShardTbl> doQuery() throws DalException {
						return shardTblDao.findAllByClusterId(migrationCluster.getClusterId(),
								ShardTblEntity.READSET_FULL);
					}
				});

				if (null != shards) {
					for (ShardTbl shard : shards) {
						MigrationShardTbl migrationShardProto = migrationShardDao.createLocal();
						migrationShardProto.setMigrationClusterId(migrationCluster.getId()).setShardId(shard.getId())
								.setLog("");
						toCreateMigrationShards.add(migrationShardProto);
					}
				}
			}
		}

		queryHandler.handleQuery(new DalQuery<Void>() {
			@Override
			public Void doQuery() throws DalException {
				migrationShardDao.insertBatch(Lists.toArray(MigrationShardTbl.class, toCreateMigrationShards));
				return null;
			}
		});
	}

	private String generateUniqueEventTag(String user) {
		StringBuilder sb = new StringBuilder();
		sb.append(DataModifiedTimeGenerator.generateModifiedTime());
		sb.append("-");
		sb.append(user);
		return sb.toString();
	}
}
