package cn.uway.igp.lte.extraDataCache.cache;

/**
 * <p>
 * Title: 平面坐标跟球面坐标进行转换
 * </p>
 * 
 * @author z.jian
 * @version 1.0
 */
public class CMapLonLat {

	public final double Rc = 6378137.00; // 赤道半径

	public final double Rj = 6356725; // 极半径

	public static final double PI = 3.14159262;

	public class TJWD {

		public double m_LoDeg, m_LoMin, m_LoSec; // longtitude 经度

		public double m_LaDeg, m_LaMin, m_LaSec;

		public double m_Longitude, m_Latitude;

		public double m_RadLo, m_RadLa;

		public double Ec, Ed;

		// 构造函数, 经度: loDeg 度, loMin 分, loSec 秒; 纬度: laDeg 度, laMin 分, laSec秒
		public TJWD(double loDeg, double loMin, double loSec, double laDeg, double laMin, double laSec) {
			m_LoDeg = loDeg;
			m_LoMin = loMin;
			m_LoSec = loSec;
			m_LaDeg = laDeg;
			m_LaMin = laMin;
			m_LaSec = laSec;
			m_Longitude = m_LoDeg + m_LoMin / 60 + m_LoSec / 3600;
			m_Latitude = m_LaDeg + m_LaMin / 60 + m_LaSec / 3600;
			m_RadLo = m_Longitude * PI / 180;
			m_RadLa = m_Latitude * PI / 180.;
			Ec = Rj + (Rc - Rj) * (90. - m_Latitude) / 90.;
			Ed = Ec * Math.cos(m_RadLa);
		}

		public void CMapLonLat() {
		}

		public TJWD(double longitude, double latitude) {
			SetPoint(longitude, latitude);
		}

		public void SetPoint(double longitude, double latitude) {
			m_LoDeg = (int) longitude;
			m_LoMin = (int) (longitude - m_LoDeg) * 60;
			m_LoSec = (longitude - m_LoDeg - m_LoMin / 60) * 3600;

			m_LaDeg = (int) (latitude);
			m_LaMin = (int) ((latitude - m_LaDeg) * 60);
			m_LaSec = (latitude - m_LaDeg - m_LaMin / 60) * 3600;

			m_Longitude = longitude;
			m_Latitude = latitude;
			m_RadLo = longitude * PI / 180;
			m_RadLa = latitude * PI / 180;
			Ec = Rj + (Rc - Rj) * (90. - m_Latitude) / 90.;
			Ed = Ec * Math.cos(m_RadLa);
		}
	}

	// ! 计算点A 和 点B的经纬度，求他们的距离和点B相对于点A的方位
	/*
	 * ! \param A A点经纬度 \param B B点经纬度 \param angle B相对于A的方位, 不需要返回该值，则将其设为空 \return A点B点的距离
	 */
	public double distance(TJWD A, TJWD B, double[] angle) {
		double dx, dy, dDeta;
		double dLo, dLa;
		dx = (B.m_RadLo - A.m_RadLo) * A.Ed;
		dy = (B.m_RadLa - A.m_RadLa) * A.Ec;
		dDeta = Math.sqrt(dx * dx + dy * dy);

		if (Math.abs(dx) < 0.00001)
			angle[0] = 0;
		else
			angle[0] = Math.atan(Math.abs(dy / dx)) * 180 / PI;
		// 判断象限
		dLo = B.m_Longitude - A.m_Longitude;
		dLa = B.m_Latitude - A.m_Latitude;
		if (dLo > 0 && dLa <= 0) // 第四
		{
			angle[0] = 360 - angle[0];
		} else if (dLo <= 0 && dLa < 0) // 第三
		{
			angle[0] = angle[0] + 180.0;
		} else if (dLo < 0 && dLa >= 0) // 第二
		{
			angle[0] = 180 - angle[0];
		}
		return dDeta;
	}

	public double distance(double longitude1, double latitude1, double longitude2, double latitude2, double[] angle) {
		TJWD jwdA, jwdB;
		jwdA = new TJWD(longitude1, latitude1);
		jwdB = new TJWD(longitude2, latitude2);
		return distance(jwdA, jwdB, angle);
	}

	public TJWD GetJWDB(TJWD A, double distance, double angle) {
		double dx, dy, BJD, BWD;
		// TJWD LocJwd ;
		dx = distance * Math.cos(angle * PI / 180.0);
		dy = distance * Math.sin(angle * PI / 180.0);

		BJD = (dx / A.Ed + A.m_RadLo) * 180.0 / PI;
		BWD = (dy / A.Ec + A.m_RadLa) * 180.0 / PI;
		TJWD pJwdStart = new TJWD(BJD, BWD);
		return pJwdStart;
	}

	public TJWD GetJWDB(double longitude, double latitude, double distance, double angle) {
		TJWD jwdA;
		jwdA = new TJWD(longitude, latitude);
		return GetJWDB(jwdA, distance, angle);
	}

	public TJWD CreateJwd(double longitude, double latitude) {
		TJWD pJwd = new TJWD(longitude, latitude);
		return pJwd;
	}

	public static void main(String[] args) {
		// new CMapLonLat().distance(A, B, angle)
	}
}
