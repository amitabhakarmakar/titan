package com.thinkaurelius.titan.graphdb.edges.persist;

import com.thinkaurelius.titan.core.PropertyType;
import com.thinkaurelius.titan.graphdb.edges.InternalEdge;
import com.thinkaurelius.titan.graphdb.edges.SimpleProperty;
import com.thinkaurelius.titan.graphdb.entitystatus.BasicEntity;
import com.thinkaurelius.titan.graphdb.vertices.InternalNode;
import com.thinkaurelius.titan.graphdb.vertices.NodeUtil;

public class PersistSimpleProperty extends SimpleProperty {

	protected BasicEntity entity;
	
	public PersistSimpleProperty(PropertyType type, InternalNode node, Object attribute) {
		super(type, node, attribute);
		entity = new BasicEntity();
	}

	public PersistSimpleProperty(PropertyType type, InternalNode node, Object attribute, long id) {
		super(type, node, attribute);
		entity = new BasicEntity(id);
	}
	
	@Override
	public int hashCode() {
		if (hasID()) return NodeUtil.getIDHashCode(this);
		else return super.hashCode();
	}

	@Override
	public boolean equals(Object oth) {
		if (oth==this) return true;
		else if (!(oth instanceof InternalEdge)) return false;
		InternalEdge other = (InternalEdge)oth;
		if (hasID() || other.hasID()) {
			if (hasID() && other.hasID()) return NodeUtil.equalIDs(this, other);
			else return false;
		}
		return super.equals(other);
	}
	
	@Override
	public void forceDelete() {
		super.forceDelete();
		entity.delete();
	}

	
	/* ---------------------------------------------------------------
	 * ID Management
	 * ---------------------------------------------------------------
	 */

	@Override
	public long getID() {
		return entity.getID();
	}



	@Override
	public boolean hasID() {
		return entity.hasID();
	}
	
	
	@Override
	public void setID(long id) {
		entity.setID(id);
	}
	

	/* ---------------------------------------------------------------
	 * LifeCycle Management
	 * ---------------------------------------------------------------
	 */

	@Override
	public boolean isModified() {
		return entity.isModified();
	}


	@Override
	public boolean isAvailable() {
		return entity.isAvailable();
	}


	@Override
	public boolean isDeleted() {
		return entity.isDeleted();
	}

	@Override
	public boolean isLoaded() {
		return entity.isLoaded();
	}


	@Override
	public boolean isNew() {
		return entity.isNew();
	}

	@Override
	public boolean isReferenceNode() {
		return entity.isReferenceNode();
	}

	


	
}