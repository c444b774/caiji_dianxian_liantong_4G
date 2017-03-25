package cn.uway.igp.lte.extraDataCache.cache;

public class CityInfo {

	public int cityId;

	public String enName;

	public int gridstartid;

	public double minLon;

	public double maxLon;

	public int getCityId() {
		return cityId;
	}

	public void setCityId(int cityId) {
		this.cityId = cityId;
	}

	public String getEnName() {
		return enName;
	}

	public void setEnName(String enName) {
		this.enName = enName;
	}

	public int getGridstartid() {
		return gridstartid;
	}

	public void setGridstartid(int gridstartid) {
		this.gridstartid = gridstartid;
	}

	public double getMinLon() {
		return minLon;
	}

	public void setMinLon(double minLon) {
		this.minLon = minLon;
	}

	public double getMaxLon() {
		return maxLon;
	}

	public void setMaxLon(double maxLon) {
		this.maxLon = maxLon;
	}

	public double getMinLat() {
		return minLat;
	}

	public void setMinLat(double minLat) {
		this.minLat = minLat;
	}

	public double getMaxLat() {
		return maxLat;
	}

	public void setMaxLat(double maxLat) {
		this.maxLat = maxLat;
	}

	public int getSid() {
		return sid;
	}

	public void setSid(int sid) {
		this.sid = sid;
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeigh() {
		return heigh;
	}

	public void setHeigh(int heigh) {
		this.heigh = heigh;
	}

	public int getGridN() {
		return gridN;
	}

	public void setGridN(int gridN) {
		this.gridN = gridN;
	}

	public int getGridM() {
		return gridM;
	}

	public void setGridM(int gridM) {
		this.gridM = gridM;
	}

	public double getLon_100() {
		return lon_100;
	}

	public void setLon_100(double lon_100) {
		this.lon_100 = lon_100;
	}

	public double getLat_100() {
		return lat_100;
	}

	public void setLat_100(double lat_100) {
		this.lat_100 = lat_100;
	}

	public double minLat;

	public double maxLat;

	public int sid;

	public double longRt;

	public double longLt;

	public double latLt;

	public double latRt;

	public int width;

	public int heigh;

	public int gridN;

	public int gridM;

	public double lon_100;

	public double lat_100;

	public CityInfo() {
		super();
	}

	public CityInfo(int cityId, String enName, int gridstartid, double longitude_lb, double longitude_rb, double latitude_lb, double latitude_rb,
			int sid, double longLt, double longRt, double latLt, double latRt) {
		super();
		this.cityId = cityId;
		this.enName = enName;
		this.gridstartid = gridstartid;
		this.minLon = longitude_lb;
		this.maxLon = longitude_rb;
		this.minLat = latitude_lb;
		this.maxLat = latitude_rb;
		this.sid = sid;
		this.longRt = longRt;
		this.longLt = longLt;
		this.latLt = latLt;
		this.latRt = latRt;
	}

	public CityInfo(int cityId, int gridstartid, double longitude_lb, double longitude_rb, double latitude_lb, double latitude_rb, int sid,
			double longLt, double longRt, double latLt, double latRt) {
		super();
		this.cityId = cityId;
		this.gridstartid = gridstartid;
		this.minLon = longitude_lb;
		this.maxLon = longitude_rb;
		this.minLat = latitude_lb;
		this.maxLat = latitude_rb;
		this.sid = sid;
		this.longRt = longRt;
		this.longLt = longLt;
		this.latLt = latLt;
		this.latRt = latRt;
	}

	@Override
	public String toString() {
		return "CityInfo [cityId=" + cityId + ", gridstartid=" + gridstartid + ", minLon=" + minLon + ", maxLon=" + maxLon + ", minLat=" + minLat
				+ ", maxLat=" + maxLat + ", sid=" + sid + "]";
	}

}
