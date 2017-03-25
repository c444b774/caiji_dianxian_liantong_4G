package cn.uway.igp.lte.service;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LteCoreCommonDataEntry {
	public int time;
	
	protected short mmec;
	protected short mmegi;
	
	protected long imsi;
	protected byte[] msisdn;
	
	// nextEntry从插入开始，是按时间从小至大排序好的
	private LteCoreCommonDataEntry nextUES1apIDEntry;
	private LteCoreCommonDataEntry nextTMSIEntry;
	
	private static final Logger log = LoggerFactory.getLogger(LteCoreCommonDataEntry.class);
	
	public Integer getMMEC() {
		// 去除负数
		int nMMEC = (mmec & 0xFFFF);
		if (nMMEC == 0xFFFF)
			return null;
		
		return nMMEC;
	}
	
	public Integer getMMEGI() {
		// 去除负数
		int nMMEGI = (mmegi & 0xFFFF);
		if (nMMEGI == 0xFFFF)
			return null;
		
		return nMMEGI;
	}
	
	public String getImsi() {
		if (imsi == 0xFFFFFFFFFFFFFFFFL)
			return null;
		
		return String.valueOf(imsi);
	}
	
	public String getMsisdn() {
		if (msisdn == null)
			return null;
		
		int length = msisdn.length-1;
		while(length >=0 && (msisdn[length] != '\0'))
			--length;
		
		if (length < 0)
			return null;
		
		return new String(msisdn, 0, length+1);
	}
	
	public LteCoreCommonDataEntry getNextUES1apIDEntry() {
		return this.nextUES1apIDEntry;
	}
	
	public LteCoreCommonDataEntry getNextTMSIEntry() {
		return this.nextTMSIEntry;
	}
	
	@Override
	public String toString() {
		return "LteCoreCommonDataEntry [time=" + time + ", mmec=" + mmec
				+ ", mmegi=" + mmegi + ", imsi=" + imsi + ", msisdn="
				+ Arrays.toString(msisdn) + "]";
	}

	/**
	 * @param newEntry
	 * @return 如果新entry排序队列，顶端发生了变化，则返回true, 否则返回false
	 * 
	 */
	public boolean addUes1apIDEntry(long mmeS1apID, LteCoreCommonDataEntry newEntry) {
		if (this.time > newEntry.time) {
			// 与上面的一条记录去重，ues1apid如果在1分钟内重复．直接抛弃.
			if (this.imsi == newEntry.imsi && this.mmec == newEntry.mmec && this.mmegi == newEntry.mmegi) {
				if (Math.abs(this.time - newEntry.time) < (60*1000))
					return false;
			}
			
			newEntry.nextUES1apIDEntry = this;
			return true;
		} else if (this.imsi == newEntry.imsi && this.mmec == newEntry.mmec && this.mmegi == newEntry.mmegi) {
			if ((newEntry.time - this.time) < (60*1000))
				return false;
		}
		
		int nFindHiearchy = 0;
		LteCoreCommonDataEntry insertAfterEntry = this;
		while (insertAfterEntry.nextUES1apIDEntry != null && insertAfterEntry.nextUES1apIDEntry.time < newEntry.time){
			insertAfterEntry = insertAfterEntry.nextUES1apIDEntry;
			
			// 与上面的一条记录去重，ues1apid如果在1分钟内重复．直接抛弃.
			if (insertAfterEntry.imsi == newEntry.imsi && insertAfterEntry.mmec == newEntry.mmec && insertAfterEntry.mmegi == newEntry.mmegi) {
				if ((newEntry.time - insertAfterEntry.time) < (60*1000))
					return false;
			}
			
			++nFindHiearchy;
			if ((nFindHiearchy%100) == 0)  {
				log.debug("addUes1apIDEntry() too most entry find. mmeS1apID:{}, hiearchy:{}, entry info:{}", new Object[]{mmeS1apID, nFindHiearchy, newEntry.toString()});
			}
		}
		
		LteCoreCommonDataEntry srcNextEntry = insertAfterEntry.nextUES1apIDEntry;
		insertAfterEntry.nextUES1apIDEntry = newEntry;
		newEntry.nextUES1apIDEntry = srcNextEntry;
		
		return false;
	}
	
	/**
	 * @param newEntry
	 * @return 如果新entry排序队列，顶端发生了变化，则返回true, 否则返回false
	 * 
	 */
	public boolean addTmsiEntry(long tmsi,LteCoreCommonDataEntry newEntry) {
		if (this.time > newEntry.time) {
			if (this.imsi == newEntry.imsi && this.mmec == newEntry.mmec && this.mmegi == newEntry.mmegi) {
				//与上面的一条记录去重，tmsi只需要保留时间最小的那条记录就可以了．(该步骤相当于丢弃了this)
				newEntry.nextTMSIEntry = this.nextTMSIEntry;
				return true;
			} else if (this.imsi == newEntry.imsi && this.mmec == newEntry.mmec && this.mmegi == newEntry.mmegi) {
				if ((newEntry.time - this.time) < (60*1000))
					return false;
			}
			
			newEntry.nextTMSIEntry = this;
			return true;
		} 
		
		int nFindHiearchy = 0;
		LteCoreCommonDataEntry insertAfterEntry = this;
		while (insertAfterEntry.nextTMSIEntry != null && insertAfterEntry.nextTMSIEntry.time < newEntry.time){
			insertAfterEntry = insertAfterEntry.nextTMSIEntry;
			// 与上面的一条记录去重，tmsi如果在1分钟内重复．直接抛弃.
			if (insertAfterEntry.imsi == newEntry.imsi && insertAfterEntry.mmec == newEntry.mmec && insertAfterEntry.mmegi == newEntry.mmegi) {
				if ((newEntry.time - insertAfterEntry.time) < (60*1000))
					return false;
			}
			
			++nFindHiearchy;
			if ((nFindHiearchy%100) == 0) 
				log.debug("addTmsiEntry() too most entry find. tmsi:{}, hiearchy:{}, entry info:{}", new Object[]{tmsi, nFindHiearchy, newEntry.toString()});
		}
		
		LteCoreCommonDataEntry srcNextEntry = insertAfterEntry.nextTMSIEntry;
		insertAfterEntry.nextTMSIEntry = newEntry;
		newEntry.nextTMSIEntry = srcNextEntry;
		
		return false;
	}
}
