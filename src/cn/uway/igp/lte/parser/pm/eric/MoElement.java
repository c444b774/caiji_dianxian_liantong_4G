package cn.uway.igp.lte.parser.pm.eric;

import java.util.List;

public class MoElement {

	String type;

	List<String[]> values;

	int hash = -1;

	public MoElement(String type, List<String[]> values) {
		super();
		this.type = type;
		this.values = values;
	}

	public String getType() {
		return type;
	}

	@Override
	public int hashCode() {
		if (hash != -1)
			return hash;

		for (String[] array : values) {
			hash += array[0].hashCode();
			hash += array[1].hashCode();
		}

		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		MoElement m = (MoElement) obj;
		if (!type.equals(m.getType()))
			return false;
		if (m.values.size() != this.values.size())
			return false;
		for (int i = 0; i < this.values.size(); i++) {
			if (!compareMoElement(this.values, m.values))
				return false;
		}
		return true;
	}

	/* 比较两个moid是否是一样的 */
	public static boolean compareMoElement(List<String[]> a, List<String[]> b) {
		if (a == b)
			return true;
		if (a.size() != b.size())
			return false;
		for (int i = 0; i < a.size(); i++) {
			if (!a.get(i)[0].equals(b.get(i)[0]) || !a.get(i)[1].equals(b.get(i)[1])) {
				return false;
			}
		}
		return true;
	}
}
