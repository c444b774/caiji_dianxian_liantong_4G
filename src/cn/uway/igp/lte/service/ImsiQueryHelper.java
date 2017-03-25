package cn.uway.igp.lte.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.context.AppContext;
import cn.uway.framework.external.AbsExternalService;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.igp.lte.service.AbsImsiQuerySession.ImsiQueryParam;
import cn.uway.igp.lte.service.AbsImsiQuerySession.ImsiRequestResult;
import cn.uway.igp.lte.service.ImsiJoinService.DistributeServer;
import cn.uway.util.ThreadUtil;

public class ImsiQueryHelper implements Runnable {

	public interface ASynchronizedParser {

		/**
		 * 异步抽取所有记录
		 * 
		 * @throws Exception
		 */
		void asynExtractAllRecords() throws Exception;
	}

	private static final Logger log = LoggerFactory
			.getLogger(ImsiQueryHelper.class);

	/**
	 * 单一个task允许的线程个数 
	 */
	private static int systemMaxJobConcurrent = AppContext.getBean("maxJobSize", Integer.class);
	
	/**
	 * 缓存的记录数
	 */
	public static final int MAX_CACHED_RECORD_SIZE = (200);

	/**
	 * 异步等待超时时间(默认1分钟)
	 */
	private static final long TIMEOUT_MILLSECOND = (5 * 60l * 1000l);

	/**
	 * 查询主Manager，客户端主要读取配置开关
	 */
	public static LteCoreCommonDataManager lteCoreCommonDataManager;

	/**
	 * 每个task一个helper;
	 */
	protected static Map<Long, ImsiQueryHelper> mapTaskHelper;

	/**
	 * 分布式服务器参数列表
	 */
	protected List<DistributeServer> distributeServerList;

	/**
	 * 分布式服务器列表
	 */
	protected ImsiQueryClientSession[] sessions;

	/**
	 * 当前采集任务id
	 */
	protected long taskID;

	/**
	 * 异步解析线程
	 */
	private Thread asyncParseThread;

	/**
	 * 异步解析Parser
	 */
	private volatile ASynchronizedParser asynParser;

	/**
	 * 取消解析标识
	 */
	private volatile boolean canceParseFlagSet;

	/**
	 * 同步初始化标识(等外部get线程来获取时，才开始异步解析，避免引发未知的错误)
	 */
	private volatile boolean syncInitFlag;

	/**
	 * 已匹配好的记录队列
	 */
	protected Queue<ImsiQueryParam> matchedRecordsQueue = new ConcurrentLinkedQueue<ImsiQueryParam>();

	/**
	 * 提次记录等待时间
	 */
	private long timeSubmitWaitMillSec = 0;
	
	/**
	 * 提交完成时间
	 */
	private long submitCompleteTime = 0;
	private long timeLogElase = 0;

	/**
	 * 待匹配的记录队列
	 */
	protected Queue<ImsiQueryParam> unMatchedRecordsQueue = new ConcurrentLinkedQueue<ImsiQueryParam>();
	
	/**
	 * 是否正在抽取记录
	 */
	private volatile boolean isExtracting;
	
	/**
	 * 正在请求中的imsi个数
	 */
	private short imsiQueryingCount;
	
	/**
	 * 正在查询的imsi参数
	 */
	private ImsiQueryParam[] imsiQueryingParams;
	
	/**
	 * 下一个helper 
	 */
	private ImsiQueryHelper nextHelper;
	
	/**
	 * 最后一次help的更新时间
	 */
	private volatile long timeMillSecHelperUpdate;
	
	/**
	 * 是否闲置状态
	 */
	private volatile boolean isIdle;
	
	/**
	 * 服务器状态
	 */
	private boolean imsiServerEnable;

	private ImsiQueryHelper(long taskID) {
		this.taskID = taskID;
		this.isIdle = true;
		this.imsiServerEnable = isImsiServerEnable();

		if (!imsiServerEnable)
			return;

		ImsiJoinService distributeService = (ImsiJoinService) AppContext
				.getBean("externalService", AbsExternalService.class);
		List<DistributeServer> distributeServerList = distributeService
				.getDistributeServersList();
		sessions = new ImsiQueryClientSession[distributeServerList.size()];

		int i = 0;
		for (DistributeServer distributeServer : distributeServerList) {
			sessions[i++] = new ImsiQueryClientSession(distributeServer, taskID);
		}
	}

	/**
	 * 根据taskID，获取helper;
	 * 
	 * @param taskID
	 * @return
	 */
	public static ImsiQueryHelper getHelperInstance(long taskID) {
		long timeCurr = System.currentTimeMillis();
		ImsiQueryHelper helper = null;
		ImsiQueryHelper idleHelper = null;
		int currTaskHelpNum = 0;
		synchronized (ImsiQueryHelper.class) {
			if (mapTaskHelper == null) {
				mapTaskHelper = new HashMap<Long, ImsiQueryHelper>();
			}
			
			helper = mapTaskHelper.get(taskID);
			if (helper == null) {
				helper = new ImsiQueryHelper(taskID);
				mapTaskHelper.put(taskID, helper);
			} 
			
			// 单线程模式直接返回，一个task一个helper
			if (systemMaxJobConcurrent <= 1)
				return helper;
			
			idleHelper = helper;
			currTaskHelpNum = 1;
			while (idleHelper != null && !idleHelper.isIdle) {
				idleHelper = idleHelper.nextHelper;
				++currTaskHelpNum;
			}
		
			if (idleHelper == null && currTaskHelpNum < 6) {
				idleHelper = new ImsiQueryHelper(taskID);
				idleHelper.nextHelper = helper;
				mapTaskHelper.put(taskID, idleHelper);
				++currTaskHelpNum;
				helper = idleHelper;
			} else if (currTaskHelpNum > 6) {
				log.warn("ImsiQueryHelper 个数超限. currTaskHelpNum={}", currTaskHelpNum);
			}
			
			if (idleHelper != null)
				idleHelper.lock();
		}
			
		// 找出闲置时间最久的helper:
		if (idleHelper == null) {
			final long timeInvalid = 30*60*1000l;
			while (idleHelper == null) {
				timeCurr = System.currentTimeMillis();
				synchronized (ImsiQueryHelper.class) {
					ImsiQueryHelper nextHelper = helper;
					ImsiQueryHelper maxUsedTimeHelper = nextHelper;
					long maxTimeOffset = 0;
					while (nextHelper != null) {
						if (nextHelper.isIdle) {
							idleHelper = nextHelper;
							idleHelper.lock();
							break;
						}
						
						long timeOffset = timeCurr - nextHelper.timeMillSecHelperUpdate;
						if (timeOffset > maxTimeOffset) {
							maxTimeOffset = timeOffset;
							maxUsedTimeHelper = nextHelper;
						}
						
						nextHelper = nextHelper.nextHelper;
					}
				
					if (idleHelper == null && (maxTimeOffset > timeInvalid)) {
						idleHelper = maxUsedTimeHelper;
						idleHelper.lock();
						log.warn("ImsiQueryHelper 个数超限. 占时用时间最久的helper已被占用时间:{}毫秒．本次将isIdle置为true", maxTimeOffset);
						break;
					}
				}
				
				try {
					if (idleHelper == null) {
						Thread.sleep(1000);
					}
				} catch (InterruptedException e) {
					return null;
				}
			}
		}
	
		return idleHelper;
	}

	public synchronized static boolean isImsiServerEnable() {
		if (lteCoreCommonDataManager == null) {
			lteCoreCommonDataManager = LteCoreCommonDataManager
					.getReadInstance();
			log.debug("IMSI查询服务器状态：{}",
					lteCoreCommonDataManager.isEnableState() ? "true" : "false");
		}

		boolean bEnable = lteCoreCommonDataManager.isEnableState();
		return bEnable;
	}

	public void destory() {
		log.debug("close all session.");
		if (sessions != null) {
			for (ImsiQueryClientSession session : sessions) {
				if (session == null)
					continue;

				session.close();
			}
		}
	}

	/**
	 * 查询IMSI服务器数据是否准备好了
	 * 
	 * @param fileTime
	 * @return 如果查询失败，返回侦测到的服务器错误
	 * @throws Exception
	 */
	public ImsiRequestResult isCacheReady(long fileTime) throws Exception {
		// 将filetime减1秒，主要是为了避免fileTime带耗毫秒数，会产生时间差
		fileTime -= 1000l;
		// 如果imsi服务被禁用，每次直接返回ready．
		if (!imsiServerEnable) {
			ImsiRequestResult result = new ImsiRequestResult();
			result.value = ImsiRequestResult.RESPONSE_CACHE_IS_READY;
			result.maxServerTimeInCache = System.currentTimeMillis();

			return result;
		}

		if (sessions.length < 1) {
			throw new Exception("未设置任何cache服务器.");
		}

		ImsiRequestResult result = null;
		for (int queryServerIndex = 0; queryServerIndex < sessions.length; ++queryServerIndex) {
			// 执行查询结果
			if (sessions[queryServerIndex] == null) {
				throw new Exception("未配置session id = " + queryServerIndex
						+ "的服务器设置.");
			}

			result = sessions[queryServerIndex].isCacheReady(fileTime);
			if (result == null
					|| result.value != ImsiRequestResult.RESPONSE_CACHE_IS_READY) {
				break;
			}
		}

		return result;
	}

	public void asynParse(ASynchronizedParser parser) {
		asynParse(parser, true);
	}

	public void asynParse(ASynchronizedParser parser, boolean isFirstFileToParse) {
		if (this.asynParser != null) {
			log.warn("ImsiQueryHelper::asynParse() the parse is not null");
			cancelParse();
		}
		// 如果是解析第一个子文件，需要等parser初始化好后，异步解析线程才可开始
		syncInitFlag = !isFirstFileToParse;

		this.unMatchedRecordsQueue.clear();
		this.matchedRecordsQueue.clear();
		this.canceParseFlagSet = false;
		this.timeSubmitWaitMillSec = 0;
		this.submitCompleteTime = 0;
		this.timeLogElase = 0;
		this.imsiQueryingParams = null;
		this.imsiQueryingCount = 0;
		
		// parser的赋值需要放在其它参数的初始化之后 
		this.asynParser = parser;

		if (asyncParseThread == null) {
			log.debug("[taskid=" + this.taskID + "] 开始启动异步关联线程．．．");
			asyncParseThread = new Thread(this, "imsi关联异步解析线程[taskid="
					+ this.taskID + "]");
			asyncParseThread.start();
		}
	}

	/**
	 * 提交查询记录
	 */
	public void submitMatchRecord(ParseOutRecord record, long cdrTime,
			Long mmeUeS1apID, Long mtmsi, Integer mmegi, Integer mmec)
			throws TimeoutException {

		if (isCanceParseFlagSet())
			return;

		ImsiQueryParam queryParam = new ImsiQueryParam(record, cdrTime,
				mmeUeS1apID, mtmsi, mmegi, mmec);

		// 如果是无效记录或imsi查询服务被关闭状态，则直接放到已匹配好的列表中，不作任何处理
		if (!queryParam.isValid() || !imsiServerEnable) {
			this.matchedRecordsQueue.offer(queryParam);
			if (this.matchedRecordsQueue.size() > MAX_CACHED_RECORD_SIZE) {
				long timeMillSecWaitStart = System.currentTimeMillis();
				while (matchedRecordsQueue.size() > MAX_CACHED_RECORD_SIZE) {
					long timeCurr = System.currentTimeMillis();
					long timeMillSecWait = timeCurr	- timeMillSecWaitStart;
					if (timeMillSecWait > TIMEOUT_MILLSECOND) {
						throw new TimeoutException("解析等待超时");
					}
					
					this.update();
					ThreadUtil.sleep(1);
				}

				timeSubmitWaitMillSec += (System.currentTimeMillis() - timeMillSecWaitStart);
			}
			
			return;
		}

		unMatchedRecordsQueue.offer(queryParam);

		if (unMatchedRecordsQueue.size() > MAX_CACHED_RECORD_SIZE) {
			long timeMillSecWaitStart = System.currentTimeMillis();
			while (unMatchedRecordsQueue.size() > MAX_CACHED_RECORD_SIZE) {
				long timeCurr = System.currentTimeMillis();
				long timeMillSecWait = timeCurr	- timeMillSecWaitStart;
				if (timeMillSecWait > TIMEOUT_MILLSECOND) {
					throw new TimeoutException("解析等待超时");
				}
				
				this.update();
				ThreadUtil.sleep(1);
			}

			timeSubmitWaitMillSec += (System.currentTimeMillis() - timeMillSecWaitStart);
		}
	}

	/**
	 * 有无已匹配或等待匹配的数据
	 * 
	 * @return
	 */
	public boolean hasMatchedRecord() {
		if (!syncInitFlag) {
			syncInitFlag = true;
			log.debug("ImsiQueryHelper解析与提取同步标识设置");
		}

		if (isCanceParseFlagSet())
			return false;
		
		if (submitCompleteTime > 0 && System.currentTimeMillis() - submitCompleteTime - timeLogElase > 1*60*1000) {
			timeLogElase = System.currentTimeMillis() - submitCompleteTime;
			log.warn("提取超时，未匹配的记录还有:{}条，已匹配待提取的记录还有：{}条", unMatchedRecordsQueue.size(), matchedRecordsQueue.size());
		}
		
		if (this.imsiQueryingCount > 0 || matchedRecordsQueue.size() > 0 || unMatchedRecordsQueue.size() > 0)
			return true;

		// 如果缓冲区都没有关联和待关联的记录，则等待asyncParseThread将asynParser置空，近回false
		while (asynParser != null && !isCanceParseFlagSet()) {
			if (matchedRecordsQueue.size() > 0
					|| unMatchedRecordsQueue.size() > 0)
				return true;
			
			this.update();
			ThreadUtil.sleep(10);
		}

		return false;
	}

	/**
	 * <pre>
	 * 获取一条匹配结果
	 * 	将getMatchedRecord放在parser的nextRecord()中，与parse()分开两个线程，
	 * 	虽然NextRecord有一定的cpu损耗，但是极其轻微可以忽略不计的，因为nextRecord只是
	 * 	将记录提交给warehouse，无阻塞．不过要考虑将所有的解析方法以及ftp下载，都应该放到
	 * asyncParse()方法中去实现
	 * </pre>
	 * 
	 * @return
	 * @throws Exception
	 */
	public ParseOutRecord getMatchedRecord() throws Exception {
		if (matchedRecordsQueue.size() > 0) {
			ImsiQueryParam queryParam = matchedRecordsQueue.poll();
			if (queryParam != null)
				return (ParseOutRecord) queryParam.queryContext;

			return null;
		}

		// 如果server是dissable状态，这里会直接返回
		if (!imsiServerEnable)
			return null;
		
		if (matchNext() > 0) {
			ImsiQueryParam queryParam = matchedRecordsQueue.poll();
			if (queryParam != null)
				return (ParseOutRecord) queryParam.queryContext;
		}
		
		return null;
	}

	/**
	 * 是否终止解析
	 * 
	 * @return
	 */
	public boolean isCanceParseFlagSet() {
		return canceParseFlagSet;
	}

	/**
	 * parser解析完，须调用一下endParse，不然cpu会负荷很高
	 */
	public void endParse() {
		syncInitFlag = false;
		release();
	}
	
	/**
	 * 兼容旧方法，单条逐一匹配
	 * 
	 * @return
	 * @throws Exception
	 */
	public ImsiRequestResult matchIMSIInfo(long cdrTime, Long mmeUeS1apID,
			Long mtmsi, Integer mmegi, Integer mmec) throws Exception {
		if (!imsiServerEnable)
			return null;

		ImsiQueryParam[] imsiQueryParams = new ImsiQueryParam[1];
		imsiQueryParams[0] = new ImsiQueryParam(null, cdrTime, mmeUeS1apID,
				mtmsi, mmegi, mmec);
		if (!imsiQueryParams[0].isValid())
			return null;

		ImsiRequestResult[] queryResults = del_matchIMSIInfo(imsiQueryParams,
				(short) 1);

		if (queryResults != null && queryResults.length > 0)
			return queryResults[0];

		return null;
	}

	/**
	 * 考虑到通讯查询可能会存在包的异步传输，导致服务端返回的结果并不是自己真实的查询结果情况， 所以单个对象查询不支持多线程同步使用
	 * 
	 * @param queryCount
	 *            查询集合数量
	 * @return
	 * @throws Exception
	 */
	private ImsiRequestResult[] del_matchIMSIInfo(ImsiQueryParam[] imsiQueryParams,
			short queryCount) throws Exception {
		if (sessions.length < 1)
			throw new Exception("未设置任何cache服务器.");

		if (imsiQueryParams == null || imsiQueryParams.length < queryCount
				|| queryCount < 1)
			return null;

		// 如果只有一个服务器，直接查询
		if (sessions.length == 1) {
			return sessions[0].matchIMSIInfo(imsiQueryParams, queryCount);
		}

		// 查询结果集
		ImsiRequestResult[] queryResult = new ImsiRequestResult[queryCount];

		// queryKeyIndex按优先查找tmsi,再查找mmeues1apid;
		for (int queryKeyIndex = 0; queryKeyIndex < ImsiQueryParam.QueryKeyCount; ++queryKeyIndex) {
			for (int sessionIndex = 0; sessionIndex < sessions.length; ++sessionIndex) {
				if (sessions[sessionIndex] == null)
					throw new Exception("未配置session id = " + sessionIndex
							+ "的服务器设置.");

				seesionQuery(imsiQueryParams, queryCount, queryKeyIndex,
						sessionIndex, queryResult);
			}
		}

		return queryResult;
	}
	
	//TODO:目前此种方式，只适合单服务器查询，多服务器查询暂不实现
	private int matchNext() throws Exception {
		if (sessions.length < 1)
			throw new Exception("未设置任何cache服务器.");
		
		try {
			// 取回上次的查询结果(如有)
			int matchRecordNum = takeMatchResult();
		
			// 如果存在待匹配的记录，发送下一批imsi的查询请求
			if (unMatchedRecordsQueue.size() < 1)
				return matchRecordNum;
			
			short imsiQueryCount = 0;
			if (this.imsiQueryingParams == null) 
				this.imsiQueryingParams = new ImsiQueryParam[ImsiQueryParam.MAX_QUERY_SIZE];
			
			// 合并要匹配的记录
			while (imsiQueryCount < ImsiQueryParam.MAX_QUERY_SIZE
					&& unMatchedRecordsQueue.size() > 0) {
				this.imsiQueryingParams[imsiQueryCount++] = unMatchedRecordsQueue.poll();
			}
	
			if (imsiQueryCount < 1)
				return matchRecordNum;
			
			sessions[0].matchIMSIInfoRequest(this.imsiQueryingParams, imsiQueryCount);
			this.imsiQueryingCount = imsiQueryCount;
			if (matchRecordNum < 1) {
				return matchNext();
			}
			
			return matchRecordNum;
		} catch (Exception e) {
			// 此处捕获的异常，一般为连接的异常．连接在出异时自动会close，所以上一次请求发送的数据，需要复位.
			this.imsiQueryingCount = 0;
			this.imsiQueryingParams = null;
			// 碰到异常，将终止本次解析内容
			cancelParse();
			throw e;
		}
	}
	
	private int takeMatchResult() throws Exception {
		// 取回上次的查询结果
		if (this.imsiQueryingCount > 0) {
			ImsiRequestResult[] queryResults = sessions[0].matchIMSIInfoTakeResult(this.imsiQueryingParams, this.imsiQueryingCount);
			if (queryResults == null)
				return 0;
			
			for (int i = 0; i < imsiQueryingCount; ++i) {
				ImsiRequestResult result = null;
				if (queryResults != null && queryResults.length > i) {
					result = queryResults[i];
				}

				// 回填imsi，手机号
				if (result != null
						&& ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS == result.value) {
					ParseOutRecord record = (ParseOutRecord) imsiQueryingParams[i].queryContext;
					Map<String, String> recordData = record.getRecord();
					String imsi = String.valueOf(result.imsi);
					recordData.put("IMSI", imsi);
					recordData.put("MSISDN", result.msisdn);
				}

				matchedRecordsQueue.offer(imsiQueryingParams[i]);
			}
			
			this.imsiQueryingCount = 0;
			return queryResults.length;
		}
		
		return 0;
	}

	/**
	 * 单台服务器按查询主键查询
	 * 
	 * @param queryCount
	 *            查询集合数量
	 * @param queryKeyIndex
	 *            查询的主键索引(0:tmsi; 1:mmes1apid)
	 * @param sessionIndex
	 *            分布式分服器index
	 * @param queryResult
	 *            查询结果集(将查询的结果回填到该数据组中)
	 * @throws Exception
	 */
	private void seesionQuery(ImsiQueryParam[] imsiQueryParams,
			short queryCount, int queryKeyIndex, int sessionIndex,
			ImsiRequestResult[] queryResult) throws Exception {

		short sessionQueryCount = 0;
		Integer[] sessionQueryObjectIndex = new Integer[queryCount];
		ImsiQueryParam[] sessionQueryObjects = new ImsiQueryParam[queryCount];

		for (int queryIndex = 0; queryIndex < queryCount; ++queryIndex) {
			// 如果查询的key不在指定的服务器，则放弃查找
			if (imsiQueryParams[queryIndex].getDistributeSessionIndex(
					queryKeyIndex, sessions.length) != sessionIndex)
				continue;

			// 已经有查询到的，不再继续查询
			if (queryResult[queryIndex] != null
					&& queryResult[queryIndex].value == ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS) {
				continue;
			}

			// 记录本次Session查询的imsiQueryParams和imsiQueryParams所在原数据中的位置
			sessionQueryObjectIndex[sessionQueryCount] = queryIndex;
			sessionQueryObjects[sessionQueryCount] = imsiQueryParams[queryIndex];
			++sessionQueryCount;
		}

		if (sessionQueryCount < 1)
			return;

		ImsiRequestResult[] sessionQueryResults = sessions[sessionIndex]
				.matchIMSIInfo(sessionQueryObjects, sessionQueryCount);
		if (sessionQueryResults == null
				|| sessionQueryResults.length != sessionQueryCount) {
			log.warn("session query result != sessionQueryCount");
			return;
		}

		for (int indexResult = 0; indexResult < sessionQueryCount; ++indexResult) {
			if (sessionQueryResults[indexResult] == null)
				continue;

			int queryIndex = sessionQueryObjectIndex[indexResult];
			queryResult[queryIndex] = sessionQueryResults[indexResult];
		}
	}

	private void cancelParse() {
		// 通知异步parse停止解析文件
		this.canceParseFlagSet = true;
		
		// 等待parse线程程完成
		while (this.isExtracting && this.asynParser != null) {
			// 在等待parser完成的同时，要清空掉队列，以防循环等待
			this.unMatchedRecordsQueue.clear();
			this.matchedRecordsQueue.clear();

			ThreadUtil.sleep(10);
		}

		this.unMatchedRecordsQueue.clear();
		this.matchedRecordsQueue.clear();
		this.imsiQueryingCount = 0;
	}

	@Override
	public void run() {
		log.debug("imsi关联异步解析线程启动成功．．．");
		while (true) {
			if (asynParser == null || isCanceParseFlagSet() || !syncInitFlag) {
				// 将isIdle状态复位
				if (systemMaxJobConcurrent > 1 
						&& !this.isIdle 
						&& (asynParser == null || isCanceParseFlagSet())) {
					synchronized (ImsiQueryHelper.class) {
						boolean timeOut = (System.currentTimeMillis() - this.timeMillSecHelperUpdate) > 5 * 60 * 1000; 
						if (timeOut && !this.isIdle) {
							boolean hasRecordMatched =(this.imsiQueryingCount > 0 || matchedRecordsQueue.size() > 0 || unMatchedRecordsQueue.size() > 0);
							if (!hasRecordMatched) {
								release();
								log.warn("ImsiQueryHelper超时未工作. 将其isIdle置为true");
							}
						}
					}
				}
				
				ThreadUtil.sleep(10);
				continue;
			}

			long timeParseStart = System.currentTimeMillis();
			try {
				// 异步调用parse
				isExtracting = true;
				asynParser.asynExtractAllRecords();
			} catch (Exception e) {
				this.canceParseFlagSet = true;
				log.error("解析出错", e);
			} finally {
				this.submitCompleteTime = System.currentTimeMillis();
				log.debug("异步调用parse结束. 共耗时:{}毫秒，其中等待提取耗时:{}毫秒", submitCompleteTime
						- timeParseStart, timeSubmitWaitMillSec);
				asynParser = null;
				isExtracting = false;
			}
		}
	}
	
	public void lock() {
		this.timeMillSecHelperUpdate = System.currentTimeMillis();
		this.isIdle = false;
	}
	
	public void release() {
		this.timeMillSecHelperUpdate = System.currentTimeMillis();
		this.isIdle = true;
	}
	
	public void update() {
		this.timeMillSecHelperUpdate = System.currentTimeMillis();
	}
}
