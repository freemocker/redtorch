package xyz.redtorch.node.master.service.impl;

import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.redtorch.node.master.rpc.service.RpcServerProcessService;
import xyz.redtorch.node.master.service.MasterTradeExecuteService;
import xyz.redtorch.node.master.service.MasterTradeRtnRelayService;
import xyz.redtorch.node.master.service.OperatorService;
import xyz.redtorch.node.master.web.socket.WebSocketServerHandler;
import xyz.redtorch.pb.CoreEnum.ExchangeEnum;
import xyz.redtorch.pb.CoreField.AccountField;
import xyz.redtorch.pb.CoreField.CommonRtnField;
import xyz.redtorch.pb.CoreField.NoticeField;
import xyz.redtorch.pb.CoreField.OrderField;
import xyz.redtorch.pb.CoreField.PositionField;
import xyz.redtorch.pb.CoreField.TickField;
import xyz.redtorch.pb.CoreField.TradeField;
import xyz.redtorch.pb.CoreRpc.RpcAccountListRtn;
import xyz.redtorch.pb.CoreRpc.RpcId;
import xyz.redtorch.pb.CoreRpc.RpcNoticeRtn;
import xyz.redtorch.pb.CoreRpc.RpcOrderListRtn;
import xyz.redtorch.pb.CoreRpc.RpcOrderRtn;
import xyz.redtorch.pb.CoreRpc.RpcPositionListRtn;
import xyz.redtorch.pb.CoreRpc.RpcTickRtn;
import xyz.redtorch.pb.CoreRpc.RpcTradeListRtn;
import xyz.redtorch.pb.CoreRpc.RpcTradeRtn;

@Service
public class MasterTradeRtnRelayServiceImpl implements MasterTradeRtnRelayService, InitializingBean {

	private Logger logger = LoggerFactory.getLogger(MasterTradeRtnRelayServiceImpl.class);

	@Autowired
	private WebSocketServerHandler webSocketServerHandler;
	@Autowired
	private RpcServerProcessService rpcServerProcessService;
	@Autowired
	private MasterTradeExecuteService masterTradeExecuteService;
	@Autowired
	private OperatorService operatorService;

	private Map<String, Long> tickTimestampFilterMap = new ConcurrentHashMap<>();
	private Map<String, Long> tickLastLocalTimestampFilterMap = new ConcurrentHashMap<>();
	private Map<String, String> tickGatewayIdFilterMap = new ConcurrentHashMap<>();

	// 对时效性要求不高的数据使用Queue减少发送次数,减轻中心节点压力
	private Queue<PositionField> positionQueue = new ConcurrentLinkedQueue<>();
	private Queue<AccountField> accountQueue = new ConcurrentLinkedQueue<>();

	private ExecutorService cachedThreadPoolService = Executors.newCachedThreadPool();
	private ExecutorService tradeRtnQueueSingleExecutorService = Executors.newSingleThreadExecutor();
	private ExecutorService marketRtnQueueSingleExecutorService = Executors.newSingleThreadExecutor();

	@Override
	public void afterPropertiesSet() throws Exception {
		cachedThreadPoolService.execute(new Runnable() {
			long lastTimestamp = System.currentTimeMillis();
			List<PositionField> positionList = new ArrayList<>();

			@Override
			public void run() {
				while (!Thread.currentThread().isInterrupted()) {
					try {
						if (positionList.size() > 50 || (System.currentTimeMillis() - lastTimestamp > 200 && !positionList.isEmpty())) {

							var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
							synchronized (operatorIdNodeIdSetMap) {
								for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {

									String operatorId = entry.getKey();
									Set<Integer> nodeIdSet = entry.getValue();

									List<PositionField> filteredPositionList = new ArrayList<>();

									for (PositionField position : positionList) {
										String accountId = position.getAccountId();

										if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
											continue;
										}
										filteredPositionList.add(position);
									}

									if (filteredPositionList.isEmpty()) {
										continue;
									}

									for (Integer nodeId : nodeIdSet) {
										if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
											continue;
										}

										Integer rtnTargetNodeId = nodeId;
										Integer rtnSourceNodeId = 0;

										CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
										commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
										commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

										RpcPositionListRtn.Builder rpcPositionRtnBuilder = RpcPositionListRtn.newBuilder();
										rpcPositionRtnBuilder.setCommonRtn(commonRtnBuilder);
										rpcPositionRtnBuilder.addAllPosition(filteredPositionList);

										cachedThreadPoolService.execute(new Runnable() {
											@Override
											public void run() {
												if(filteredPositionList.size()>3) {
													rpcServerProcessService.sendLz4CoreRpc(rtnTargetNodeId, rpcPositionRtnBuilder.build().toByteString(), "",
															RpcId.POSITION_LIST_RTN);
												}else {
													rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcPositionRtnBuilder.build().toByteString(), "",
															RpcId.POSITION_LIST_RTN);
												}
											}
										});
										
									}
								}
							}

							lastTimestamp = System.currentTimeMillis();
							positionList = new ArrayList<>();
						}

						if (positionQueue.peek() != null) {
							positionList.add(positionQueue.poll());
						} else {
							Thread.sleep(5);
						}

					} catch (Exception e) {
						logger.error("定时发送持仓列表线程异常", e);
					}
				}
			}
		});

		cachedThreadPoolService.execute(new Runnable() {
			long lastTimestamp = System.currentTimeMillis();
			List<AccountField> accountList = new ArrayList<>();

			@Override
			public void run() {
				while (!Thread.currentThread().isInterrupted()) {
					try {
						if (accountList.size() > 50 || (System.currentTimeMillis() - lastTimestamp > 200 && !accountList.isEmpty())) {

							var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
							synchronized (operatorIdNodeIdSetMap) {
								for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {

									String operatorId = entry.getKey();
									Set<Integer> nodeIdSet = entry.getValue();

									List<AccountField> filteredAccountList = new ArrayList<>();

									for (AccountField account : accountList) {
										String accountId = account.getAccountId();

										if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
											continue;
										}
										filteredAccountList.add(account);
									}

									if (filteredAccountList.isEmpty()) {
										continue;
									}

									for (Integer nodeId : nodeIdSet) {
										if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
											continue;
										}

										Integer rtnTargetNodeId = nodeId;
										Integer rtnSourceNodeId = 0;

										CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
										commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
										commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

										RpcAccountListRtn.Builder rpcAccountRtnBuilder = RpcAccountListRtn.newBuilder();
										rpcAccountRtnBuilder.setCommonRtn(commonRtnBuilder);
										rpcAccountRtnBuilder.addAllAccount(filteredAccountList);
										
										
										cachedThreadPoolService.execute(new Runnable() {
											@Override
											public void run() {
												if(filteredAccountList.size()>3) {
													rpcServerProcessService.sendLz4CoreRpc(rtnTargetNodeId, rpcAccountRtnBuilder.build().toByteString(), "",
															RpcId.ACCOUNT_LIST_RTN);
												}else {
													rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcAccountRtnBuilder.build().toByteString(), "",
															RpcId.ACCOUNT_LIST_RTN);
												}
											}
										});

										
									}
								}
							}

							lastTimestamp = System.currentTimeMillis();
							accountList = new ArrayList<>();
						}

						if (accountQueue.peek() != null) {
							accountList.add(accountQueue.poll());
						} else {
							Thread.sleep(5);
						}

					} catch (Exception e) {
						logger.error("定时发送账户列表线程异常", e);
					}
				}
			}
		});

	}

	@Override
	public void onOrder(CommonRtnField commonRtn, OrderField order) {
		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();

		synchronized (operatorIdNodeIdSetMap) {
			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {

				String operatorId = entry.getKey();
				Set<Integer> nodeIdSet = entry.getValue();

				String accountId = order.getAccountId();

				if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
					continue;
				}

				for (Integer nodeId : nodeIdSet) {
					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
						continue;
					}

					Integer rtnTargetNodeId = nodeId;
					Integer rtnSourceNodeId = 0;

					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

					RpcOrderRtn.Builder rpcOrderRtnBuilder = RpcOrderRtn.newBuilder();
					rpcOrderRtnBuilder.setCommonRtn(commonRtnBuilder);
					rpcOrderRtnBuilder.setOrder(order);

					tradeRtnQueueSingleExecutorService.execute(new Runnable() {
						@Override
						public void run() {
							rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcOrderRtnBuilder.build().toByteString(), "", RpcId.ORDER_RTN);
							
						}
					});
				}
			}
		}

	}

	@Override
	public void onTrade(CommonRtnField commonRtn, TradeField trade) {
		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();

		synchronized (operatorIdNodeIdSetMap) {
			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {
				String operatorId = entry.getKey();
				Set<Integer> nodeIdSet = entry.getValue();

				String accountId = trade.getAccountId();

				if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
					continue;
				}

				for (Integer nodeId : nodeIdSet) {
					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
						continue;
					}

					Integer rtnTargetNodeId = nodeId;
					Integer rtnSourceNodeId = 0;

					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

					RpcTradeRtn.Builder rpcTradeRtnBuilder = RpcTradeRtn.newBuilder();
					rpcTradeRtnBuilder.setCommonRtn(commonRtnBuilder);
					rpcTradeRtnBuilder.setTrade(trade);

					tradeRtnQueueSingleExecutorService.execute(new Runnable() {
						@Override
						public void run() {
							rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcTradeRtnBuilder.build().toByteString(), "", RpcId.TRADE_RTN);
						}
					});
				}
			}

		}

	}

	@Override
	public void onTick(CommonRtnField commonRtn, TickField tick) {

		// --------------------------先根据dataSourceId进行转发--------------------------
		Set<Integer> dataSourceIdSubscribedNodeIdSet = masterTradeExecuteService.getSubscribedNodeIdSet(tick.getDataSourceId());

		for (Integer nodeId : dataSourceIdSubscribedNodeIdSet) {
			Integer rtnTargetNodeId = nodeId;
			Integer rtnSourceNodeId = 0;

			CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
			commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
			commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

			RpcTickRtn.Builder rpcTickRtnBuilder = RpcTickRtn.newBuilder();
			rpcTickRtnBuilder.setCommonRtn(commonRtnBuilder);
			rpcTickRtnBuilder.setTick(tick);

			marketRtnQueueSingleExecutorService.execute(new Runnable() {
				@Override
				public void run() {
					rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcTickRtnBuilder.build().toByteString(), "", RpcId.TICK_RTN);
					
				}
			});

		}

		// --------------------------过滤行情-----------------------------
		String unifiedSymbol = tick.getContract().getUnifiedSymbol();

		long actionTimestamp = tick.getActionTimestamp();

		if (tick.getContract().getExchange() == ExchangeEnum.CZCE) {
			if (tickGatewayIdFilterMap.containsKey(unifiedSymbol)) {
				String gatewayId = tickGatewayIdFilterMap.get(unifiedSymbol);
				if (!gatewayId.equals(tick.getGateway().getGatewayId())) {
					if (tickLastLocalTimestampFilterMap.containsKey(unifiedSymbol)) {
						if (System.currentTimeMillis() - tickLastLocalTimestampFilterMap.get(unifiedSymbol) > 5 * 1000) {
							logger.warn("超过5秒未能接收到网关发送的郑商所合约行情数据,切换网关数据,网关ID:{},合约统一标识:{}", gatewayId, unifiedSymbol);
						} else {
							return;
						}
					} else {
						return;
					}
				}

			}

			tickGatewayIdFilterMap.put(unifiedSymbol, tick.getGateway().getGatewayId());
			tickLastLocalTimestampFilterMap.put(unifiedSymbol, System.currentTimeMillis());
		} else {
			if (tickTimestampFilterMap.containsKey(unifiedSymbol)) {
				if (actionTimestamp <= tickTimestampFilterMap.get(unifiedSymbol)) {
					return;
				}
			}
		}

		tickTimestampFilterMap.put(unifiedSymbol, actionTimestamp);

		// --------------------------先根据unfiedSymbol进行转发--------------------------
		Set<Integer> unfiedSymbolSubscribedNodeIdSet = masterTradeExecuteService.getSubscribedNodeIdSet(tick.getContract().getUnifiedSymbol());

		for (Integer nodeId : unfiedSymbolSubscribedNodeIdSet) {
			// 过滤已经转发过的dataSourceId
			if (dataSourceIdSubscribedNodeIdSet.contains(nodeId)) {
				continue;
			}
			Integer rtnTargetNodeId = nodeId;
			Integer rtnSourceNodeId = 0;

			CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
			commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
			commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

			RpcTickRtn.Builder rpcTickRtnBuilder = RpcTickRtn.newBuilder();
			rpcTickRtnBuilder.setCommonRtn(commonRtnBuilder);
			rpcTickRtnBuilder.setTick(tick);

			
			marketRtnQueueSingleExecutorService.execute(new Runnable() {
				@Override
				public void run() {
					rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcTickRtnBuilder.build().toByteString(), "", RpcId.TICK_RTN);
					
				}
			});

		}
	}

	@Override
	public void onAccount(CommonRtnField commonRtn, AccountField account) {

		accountQueue.add(account);

//		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
//
//		synchronized (operatorIdNodeIdSetMap) {
//			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {
//
//				String operatorId = entry.getKey();
//				Set<Integer> nodeIdSet = entry.getValue();
//
//				String accountId = account.getAccountId();
//
//				if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
//					continue;
//				}
//
//				for (Integer nodeId : nodeIdSet) {
//
//					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
//						continue;
//					}
//
//					Integer rtnTargetNodeId = nodeId;
//					Integer rtnSourceNodeId = 0;
//
//					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
//					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
//					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);
//
//					RpcAccountRtn.Builder rpcAccountRtnBuilder = RpcAccountRtn.newBuilder();
//					rpcAccountRtnBuilder.setCommonRtn(commonRtnBuilder);
//					rpcAccountRtnBuilder.setAccount(account);
//
//					rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId,
//							rpcAccountRtnBuilder.build().toByteString(), "", RpcId.ACCOUNT_RTN);
//				}
//			}
//		}

	}

	@Override
	public void onPosition(CommonRtnField commonRtn, PositionField position) {

		positionQueue.add(position);

//		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
//		synchronized (operatorIdNodeIdSetMap) {
//			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {
//
//				String operatorId = entry.getKey();
//				Set<Integer> nodeIdSet = entry.getValue();
//
//				String accountId = position.getAccountId();
//
//				if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
//					continue;
//				}
//
//				for (Integer nodeId : nodeIdSet) {
//					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
//						continue;
//					}
//
//					Integer rtnTargetNodeId = nodeId;
//					Integer rtnSourceNodeId = 0;
//
//					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
//					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
//					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);
//
//					RpcPositionRtn.Builder rpcPositionRtnBuilder = RpcPositionRtn.newBuilder();
//					rpcPositionRtnBuilder.setCommonRtn(commonRtnBuilder);
//					rpcPositionRtnBuilder.setPosition(position);
//
//					rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId,
//							rpcPositionRtnBuilder.build().toByteString(), "", RpcId.POSITION_RTN);
//				}
//			}
//		}
	}

	@Override
	public void onNotice(CommonRtnField commonRtn, NoticeField notice) {
		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();

		synchronized (operatorIdNodeIdSetMap) {
			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {
				Set<Integer> nodeIdSet = entry.getValue();

				for (Integer nodeId : nodeIdSet) {

					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
						continue;
					}

					Integer rtnTargetNodeId = nodeId;
					Integer rtnSourceNodeId = 0;

					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

					RpcNoticeRtn.Builder rpcNoticeRtnBuilder = RpcNoticeRtn.newBuilder();
					rpcNoticeRtnBuilder.setCommonRtn(commonRtnBuilder);
					rpcNoticeRtnBuilder.setNotice(notice);

					cachedThreadPoolService.execute(new Runnable() {
						@Override
						public void run() {
							rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcNoticeRtnBuilder.build().toByteString(), "", RpcId.NOTICE_RTN);
							
						}
					});
				}

			}
		}

	}

	@Override
	public void onOrderList(CommonRtnField commonRtn, List<OrderField> orderList) {
		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
		synchronized (operatorIdNodeIdSetMap) {
			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {

				String operatorId = entry.getKey();
				Set<Integer> nodeIdSet = entry.getValue();

				List<OrderField> filteredOrderList = new ArrayList<>();

				for (OrderField order : orderList) {
					String accountId = order.getAccountId();

					if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
						continue;
					}
					filteredOrderList.add(order);
				}

				if (filteredOrderList.isEmpty()) {
					continue;
				}

				for (Integer nodeId : nodeIdSet) {
					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
						continue;
					}

					Integer rtnTargetNodeId = nodeId;
					Integer rtnSourceNodeId = 0;

					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

					RpcOrderListRtn.Builder rpcOrderRtnBuilder = RpcOrderListRtn.newBuilder();
					rpcOrderRtnBuilder.setCommonRtn(commonRtnBuilder);
					rpcOrderRtnBuilder.addAllOrder(filteredOrderList);
					
					tradeRtnQueueSingleExecutorService.execute(new Runnable() {
						@Override
						public void run() {
							rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcOrderRtnBuilder.build().toByteString(), "", RpcId.ORDER_LIST_RTN);
						}
					});

				}
			}
		}

	}

	@Override
	public void onTradeList(CommonRtnField commonRtn, List<TradeField> tradeList) {
		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
		synchronized (operatorIdNodeIdSetMap) {
			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {

				String operatorId = entry.getKey();
				Set<Integer> nodeIdSet = entry.getValue();

				List<TradeField> filteredTradeList = new ArrayList<>();

				for (TradeField trade : tradeList) {
					String accountId = trade.getAccountId();

					if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
						continue;
					}
					filteredTradeList.add(trade);
				}

				if (filteredTradeList.isEmpty()) {
					continue;
				}

				for (Integer nodeId : nodeIdSet) {
					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
						continue;
					}

					Integer rtnTargetNodeId = nodeId;
					Integer rtnSourceNodeId = 0;

					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);

					RpcTradeListRtn.Builder rpcTradeRtnBuilder = RpcTradeListRtn.newBuilder();
					rpcTradeRtnBuilder.setCommonRtn(commonRtnBuilder);
					rpcTradeRtnBuilder.addAllTrade(filteredTradeList);

					tradeRtnQueueSingleExecutorService.execute(new Runnable() {
						@Override
						public void run() {
							rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId, rpcTradeRtnBuilder.build().toByteString(), "", RpcId.TRADE_LIST_RTN);
						}
					});
				}
			}
		}
	}

	@Override
	public void onTickList(CommonRtnField commonRtn, List<TickField> tickList) {
		for (TickField tick : tickList) {
			this.onTick(commonRtn, tick);
		}
	}

	@Override
	public void onPositionList(CommonRtnField commonRtn, List<PositionField> positionList) {

		positionQueue.addAll(positionList);

//		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
//		synchronized (operatorIdNodeIdSetMap) {
//			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {
//
//				String operatorId = entry.getKey();
//				Set<Integer> nodeIdSet = entry.getValue();
//				
//				List<PositionField> filteredPositionList = new ArrayList<>();
//				
//				for(PositionField position:positionList) {
//					String accountId = position.getAccountId();
//
//					if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
//						continue;
//					}
//					filteredPositionList.add(position);
//				}
//				
//				if(filteredPositionList.isEmpty()) {
//					continue;
//				}
//
//				for (Integer nodeId : nodeIdSet) {
//					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
//						continue;
//					}
//
//					Integer rtnTargetNodeId = nodeId;
//					Integer rtnSourceNodeId = 0;
//
//					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
//					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
//					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);
//
//					RpcPositionListRtn.Builder rpcPositionRtnBuilder = RpcPositionListRtn.newBuilder();
//					rpcPositionRtnBuilder.setCommonRtn(commonRtnBuilder);
//					rpcPositionRtnBuilder.addAllPosition(filteredPositionList);
//
//					rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId,
//							rpcPositionRtnBuilder.build().toByteString(), "", RpcId.POSITION_LIST_RTN);
//				}
//			}
//		}

	}

	@Override
	public void onAccountList(CommonRtnField commonRtn, List<AccountField> accountList) {
		accountQueue.addAll(accountList);

//		var operatorIdNodeIdSetMap = webSocketServerHandler.getOperatorIdNodeIdSetMap();
//		synchronized (operatorIdNodeIdSetMap) {
//			for (Entry<String, Set<Integer>> entry : operatorIdNodeIdSetMap.entrySet()) {
//
//				String operatorId = entry.getKey();
//				Set<Integer> nodeIdSet = entry.getValue();
//				
//				List<AccountField> filteredAccountList = new ArrayList<>();
//				
//				for(AccountField account:accountList) {
//					String accountId = account.getAccountId();
//
//					if (!operatorService.checkReadAccountPermission(operatorId, accountId)) {
//						continue;
//					}
//					filteredAccountList.add(account);
//				}
//				
//				if(filteredAccountList.isEmpty()) {
//					continue;
//				}
//
//				for (Integer nodeId : nodeIdSet) {
//					if (webSocketServerHandler.getSkipTradeEventsNodeIdSet().contains(nodeId)) {
//						continue;
//					}
//
//					Integer rtnTargetNodeId = nodeId;
//					Integer rtnSourceNodeId = 0;
//
//					CommonRtnField.Builder commonRtnBuilder = CommonRtnField.newBuilder();
//					commonRtnBuilder.setTargetNodeId(rtnTargetNodeId);
//					commonRtnBuilder.setSourceNodeId(rtnSourceNodeId);
//
//					RpcAccountListRtn.Builder rpcAccountRtnBuilder = RpcAccountListRtn.newBuilder();
//					rpcAccountRtnBuilder.setCommonRtn(commonRtnBuilder);
//					rpcAccountRtnBuilder.addAllAccount(filteredAccountList);
//
//					rpcServerProcessService.sendRoutineCoreRpc(rtnTargetNodeId,
//							rpcAccountRtnBuilder.build().toByteString(), "", RpcId.ACCOUNT_LIST_RTN);
//				}
//			}
//		}

	}

}