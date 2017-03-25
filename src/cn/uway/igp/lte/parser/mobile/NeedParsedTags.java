package cn.uway.igp.lte.parser.mobile;

import java.util.HashSet;
import java.util.Set;

public class NeedParsedTags {

	public static Set<String> tagsSet = new HashSet<String>();

	static {
		tagsSet.add("CDMAmainservicecells");

		tagsSet.add("LTEmainservicecells");

		tagsSet.add("cityinfo");

		tagsSet.add("imsi");

		tagsSet.add("Reportingtime");

		tagsSet.add("callinfo");

		tagsSet.add("pinginfo");

		tagsSet.add("ftpupinfo");

		tagsSet.add("ftpdowninfo");

		tagsSet.add("measureType");

		tagsSet.add("rssi");

		tagsSet.add("ecio");

		tagsSet.add("dorssi");

		tagsSet.add("doecio");

		tagsSet.add("sinr");

		tagsSet.add("rsrp");

		tagsSet.add("rsrq");

		tagsSet.add("ltesinr");

		tagsSet.add("xAxis");

		tagsSet.add("xAxislte");

		tagsSet.add("cdmasidnidci");

		tagsSet.add("ltetacpci");
	}

}
