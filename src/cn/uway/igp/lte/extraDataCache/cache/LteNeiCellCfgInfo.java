package cn.uway.igp.lte.extraDataCache.cache;


public class LteNeiCellCfgInfo {
	// 用于关联到小区信息的key (厂家编号(16bit) + eNodeBID(32bit) + cellID(16bit)
	public long cellInfoKey;
	//小区信息
	private LteCellCfgInfo cellInfo;
	// 邻区网元pci
	public short nei_pci;
	// 距离
	public float distance;
	
	public LteCellCfgInfo getCellInfo() {
		if (cellInfo == null && cellInfoKey > 0) {
			short vendor = (short)((cellInfoKey >> 48) & 0xFFFFL);
			int enbID = (int)((cellInfoKey >> 16) & 0xFFFFFFFFL);
			short cellID = (short)(cellInfoKey & 0xFFFFL);
			
			String vendorCode = "0000" + vendor;
			String findKey = "ZY" + vendorCode.substring(vendorCode.length()-4) + "-" + enbID + "-" + cellID;
			
			cellInfo = LteCellCfgCache.findNeCellByVendorEnbCell(findKey); 
		}
		
		return cellInfo;
	}
	
	public static long buildCellInfoKey(String vendor, String enbID, String cellID) {
		if (vendor == null || vendor.length()!=6)
			return 0;
		short nVendor = Short.valueOf(vendor.substring(2));
		
		if (enbID == null || enbID.length()<1)
			return 0;
		Integer nEnbID = Integer.valueOf(enbID);
		
		if (cellID == null || cellID.length()<1)
			return 0;
		Short nCellID = Short.valueOf(cellID);
		
		return buildCellInfoKey(nVendor, nEnbID, nCellID);
	}
	
	public static long buildCellInfoKey(Short vendor, Integer enbID, Short cellID) {
		if (vendor == null || enbID == null || cellID == null)
			return 0;
		
		return ((vendor & 0xFFFFL) << 48) | ((enbID & 0xFFFFFFFFL) << 16) | (cellID & 0xFFFFL);
		
	}
}
