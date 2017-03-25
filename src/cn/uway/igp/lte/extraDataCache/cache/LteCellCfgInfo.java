package cn.uway.igp.lte.extraDataCache.cache;


public class LteCellCfgInfo {
	public int cityId;
	
	public String cityName;
	
	public String neEnbId;
	
	public String neCellId;
	
	public int countyId;
	
	public String countyName;
	
	public String fddTddInd;
	
	public Double longitude;
	
	public Double latitude;
	
	public Double direct_angle;
	//天线挂高
	public Double antenna_high;
	//室内室外类型
	public String location_type;
	//频点
	public Double dl_ear_fcn;
	//城区郊区
	public String coverage_area;
	
	//是否是来自于MR (只有邻区才有)
	public Boolean isMR;
	//邻区PCI (只有邻区才有)
	public Short nei_pci;
	//距离 (只有邻区才有)
	public Double distance;
}
