package cn.uway.igp.lte.context.common.pojo;

import cn.uway.framework.connection.FTPConnectionInfo;

/**
 * SystemConfig 针对话单的系统全局配置 多个采集服务器共享
 * 
 * @author chenrongqiang 2012-11-9
 */
public class CommonSystemConfig{

	/**
	 * 当CDL网元关联不上是否需要输出到原始话单记录中，false为否，true为需要，取false值会把此CDL记录到bad文件中
	 */
	private boolean neNotExistIgnore = true;

	/**
	 * 网元数据每天加载的时间点，取值0-23
	 */
	private int neReloadSchedule = 5;

	/**
	 * 输出给汇总的中间文件的后缀名 默认为csv
	 */
	private String baseSummaryFileExt = "csv";

	/**
	 * 输出给汇总的中间文件数据中各field的分隔符 默认以，分割
	 */
	private String baseSummaryFileSplit = ",";

	/**
	 * 输出给汇总的完整性控制文件的后缀名
	 */
	private String baseSummaryOkFileExt = "ok";

	/**
	 * 完整性ok文件规则扫描周期，单位分钟
	 */
	private int baseSummaryOkFileScanPeriod = 1;

	/**
	 * 外部统一服务 FTP地址
	 */
	private FTPConnectionInfo connectionInfo;

	public String getBaseSummaryOkFileExt(){
		return baseSummaryOkFileExt;
	}

	public void setBaseSummaryOkFileExt(String baseSummaryOkFileExt){
		this.baseSummaryOkFileExt = baseSummaryOkFileExt;
	}

	public boolean isNeNotExistIgnore(){
		return neNotExistIgnore;
	}

	public int getNeReloadSchedule(){
		return neReloadSchedule;
	}

	public String getBaseSummaryFileExt(){
		return baseSummaryFileExt;
	}

	public String getBaseSummaryFileSplit(){
		return baseSummaryFileSplit;
	}

	public int getBaseSummaryOkFileScanPeriod(){
		return baseSummaryOkFileScanPeriod;
	}

	public void setNeNotExistIgnore(boolean neNotExistIgnore){
		this.neNotExistIgnore = neNotExistIgnore;
	}

	public void setNeReloadSchedule(int neReloadSchedule){
		this.neReloadSchedule = neReloadSchedule;
	}

	public void setBaseSummaryFileExt(String baseSummaryFileExt){
		this.baseSummaryFileExt = baseSummaryFileExt;
	}

	public void setBaseSummaryFileSplit(String baseSummaryFileSplit){
		this.baseSummaryFileSplit = baseSummaryFileSplit;
	}

	public void setBaseSummaryOkFileScanPeriod(int baseSummaryOkFileScanPeriod){
		this.baseSummaryOkFileScanPeriod = baseSummaryOkFileScanPeriod;
	}

	public FTPConnectionInfo getConnectionInfo(){
		return connectionInfo;
	}

	public void setConnectionInfo(FTPConnectionInfo connectionInfo){
		this.connectionInfo = connectionInfo;
	}

	@Override
	public String toString() {
		return "CommonSystemConfig [neNotExistIgnore=" + neNotExistIgnore + ", neReloadSchedule=" + neReloadSchedule
				+ ", baseSummaryFileExt=" + baseSummaryFileExt + ", baseSummaryFileSplit=" + baseSummaryFileSplit
				+ ", baseSummaryOkFileExt=" + baseSummaryOkFileExt + ", baseSummaryOkFileScanPeriod=" + baseSummaryOkFileScanPeriod
				+ ", connectionInfo=" + connectionInfo + "]";
	}

}
