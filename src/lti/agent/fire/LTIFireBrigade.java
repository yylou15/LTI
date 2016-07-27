package lti.agent.fire;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import kernel.KernelConstants;

import area.Sector;
import area.Sectorization;
import lti.agent.AbstractLTIAgent;
import lti.message.Message;
import lti.message.type.TaskDrop;
import lti.message.type.TaskPickup;
import lti.message.type.Victim;
import lti.message.type.VictimDied;
import lti.message.type.VictimRescued;
import lti.utils.BuildingPoint;
import lti.utils.EntityIDComparator;
import lti.utils.GrahamScan;
import lti.utils.Point2D;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Hydrant;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityConstants;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;

public class LTIFireBrigade extends AbstractLTIAgent<FireBrigade> {

	private static final String MAX_WATER_KEY = "fire.tank.maximum";
	private static final String MAX_DISTANCE_KEY = "fire.extinguish.max-distance";
	private static final String MAX_POWER_KEY = "fire.extinguish.max-sum";

	private int maxWater;
	private int maxDistance;
	private int maxPower;
	private List<EntityID> refuges;
	private List<EntityID> fireBrigadesList;
	private int dangerousDistance;
	private Set<EntityID> burntBuildings;
	private List<EntityID> path;
	private List<Pair<EntityID, EntityID>> transitionsBlocked;
	
	private static enum State {
		RETURNING_TO_SECTOR, MOVING_TO_REFUGE, MOVING_TO_HYDRANT, MOVING_TO_FIRE, 
		MOVING_TO_GAS, MOVING_CLOSE_TO_GAS, RANDOM_WALKING, 
		TAKING_ALTERNATE_ROUTE, RESUMING_RANDOM_WALKING,
		EXTINGUISHING_FIRE, REFILLING, DEAD, BURIED
	};

	private State state;
	
	private DistanceComparator DISTANCE_COMPARATOR;
	
	private Sectorization sectorization;
	
	private Sector sector;
	private int numberOfCyclesDistantFromFire;

	@Override
	protected void postConnect() {
		super.postConnect();
		currentX = me().getX();
		currentY = me().getY();
		numberOfCyclesDistantFromFire = 0;
		
		DISTANCE_COMPARATOR = new DistanceComparator(this.getID());
		
		Set<EntityID> fireBrigades = new TreeSet<EntityID>(
				new EntityIDComparator());

		for (StandardEntity e : model
				.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
			fireBrigades.add(e.getID());
		}

		fireBrigadesList = new ArrayList<EntityID>(fireBrigades);
		
		internalID = fireBrigadesList.indexOf(me().getID()) + 1;

		model.indexClass(StandardEntityURN.BUILDING, StandardEntityURN.REFUGE);
		maxWater = config.getIntValue(MAX_WATER_KEY);
		maxDistance = config.getIntValue(MAX_DISTANCE_KEY);
		maxPower = config.getIntValue(MAX_POWER_KEY);

		refuges = new ArrayList<EntityID>();
		List<Refuge> ref = getRefuges();

		for (Refuge r : ref) {
			refuges.add(r.getID());
		}
		
		dangerousDistance = 25000;
		
		burntBuildings = new HashSet<EntityID>();
		transitionsBlocked = new ArrayList<Pair<EntityID, EntityID>>(MAX_TIMESTEPS_TO_KEEP_BLOCKED_PATHS);
		for (int i = 0; i < MAX_TIMESTEPS_TO_KEEP_BLOCKED_PATHS; i++) {
			transitionsBlocked.add(i, null);
		}
		
		defineSectorRelatedVariables();
		
		changeState(State.RANDOM_WALKING);
	}

	@Override
	protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
		return EnumSet.of(StandardEntityURN.FIRE_BRIGADE);
	}

	/**
	 * Define the number of divisions, sectorize the world, print the sectors
	 * into a file, define the working sector of this instance of the agent and
	 * keep the list of the sectors that can be used during the simulation as a
	 * working sector
	 */
	private void defineSectorRelatedVariables() {
		sectorization = new Sectorization(model, neighbours,
				fireBrigadesList.size(), verbose);

		sector = sectorization.getSector(internalID);

		log("Defined sector: " + sector);
	}
	
	protected void think(int time, ChangeSet changed, Collection<Command> heard) {
		super.think(time, changed, heard);
		transitionsBlocked.set(currentTime % MAX_TIMESTEPS_TO_KEEP_BLOCKED_PATHS, null);

		if (me().getHP() == 0) {
			changeState(State.DEAD);
			return;
		}

		/*sendMessageAboutPerceptions(changed);*/
		
		int bad=0;;
		for(Command next : heard){
			if (!goodCommunication(next)){
				bad++;
			}
		}
		if (bad>BAD_COMUNICATION){
			sendMessageAboutPerceptions(changed, false);
		}
		else{
			sendMessageAboutPerceptions(changed, true);
		}
		
		
		if (me().getBuriedness() != 0) {
			changeState(State.BURIED);
			return;
		}
		
		// Verify if you are blocked
		if (amIBlocked(time)) {
			log("Blocked! Random walk to escape");
			if (path != null && path.size() >= 1 &&
					path.indexOf(currentPosition) < path.size()-1) {
				int ii = currentTime % MAX_TIMESTEPS_TO_KEEP_BLOCKED_PATHS;
				
				EntityID ee = path.get(0);
				if (path.indexOf(currentPosition) >= 0)
					ee = path.get(path.indexOf(currentPosition)+1);
				
				transitionsBlocked.set(ii,
						new Pair<EntityID, EntityID>(currentPosition, ee));
			}
			path = randomWalk();
			sendMove(time, path);
			return;
		}

		dropTask(time, changed);

		if (target == null) {
			target = selectTask();
		}

		// There is no need to stay inside a burning building, right?
		if (location() instanceof Building) {
			if (((Building) location()).isOnFire()) {
				path = randomWalk();

				sendMove(time, path);
				log("Leaving a burning building");
				return;
			}
		}

		// Am I at a refuge or a hydrant?
		if ((location() instanceof Refuge || location() instanceof Hydrant) && me().isWaterDefined()
				&& me().getWater() < maxWater) {
			sendRest(time);
			changeState(State.REFILLING);
			return;
		}

		Set<Pair<EntityID, EntityID>> transitionsSet = getTransitionsSet();
		
		// Am I out of water?
		if (me().isWaterDefined() && me().getWater() == 0) {
			path = search.breadthFirstSearchAvoidingBlockedRoads(
				currentPosition,
				transitionsSet,
				refuges
			);
			changeState(State.MOVING_TO_REFUGE);

			if (path == null) {
				path = randomWalk();
				log("Trying to move to refugee, but couldn't find path");
			}
			target = path.get(path.size() - 1);
			sendMove(time, path);
			return;
		}

		if (target != null) {
			LinkedList<EntityID> targetCluster = getFireCluster(target);
			
			if(targetCluster.size() > 1){
				List<EntityID> convexHull = getConvexHull(targetCluster);
				List<EntityID> aux = new ArrayList<EntityID>(convexHull);
				
				for(EntityID id : convexHull){
					log("Convex Hull contains: " + id);
				}
				
				// we then check for the closest building on fire
				for (EntityID entityID : convexHull) {
					Building building = (Building) model.getEntity(entityID);
					
					if(building.isFierynessDefined()){
						if (building.getFierynessEnum().equals(StandardEntityConstants.Fieryness.BURNT_OUT)){
							aux.remove(entityID);
						}
					}
				}
				
				if (aux.size() >= 1) {
					target = aux.get(this.internalID % aux.size());
					
					log("Convex Hull - Target from: " + target);
				}
			}
			
			if (target != null) {
				// Once the target is determined, we refresh the tasks
				this.refreshMyTasks(target);
				
				if (changed.getChangedEntities().contains(target))
					numberOfCyclesDistantFromFire = 0;
				if (model.getDistance(location().getID(), target) < maxDistance) {
					if (!changed.getChangedEntities().contains(target))
						numberOfCyclesDistantFromFire++;
					if (numberOfCyclesDistantFromFire < 5) {
						sendExtinguish(time, target, maxPower);
						changeState(State.EXTINGUISHING_FIRE);
						return;
					}
				}
				
				path = search.breadthFirstSearchAvoidingBlockedRoads(
					currentPosition,
					transitionsSet,
					target
				);

				if (path != null) {
					path.remove(path.size() - 1);
					sendMove(time, path);
					
					changeState(State.MOVING_TO_FIRE);

					if (!path.isEmpty()) {
						target = path.get(path.size() - 1);
					}

					return;
				}
			}
		}
		
		// If it has nothing to do and water level is below 80%, start finding a place to refill it
		if(me().isWaterDefined() && me().getWater() < 0.8*this.maxWater){
			log("I have nothing to do, i'll reffil, my water level is at " + ((float) me().getWater()/this.maxWater) + "!");
			
			// We analyze first if the level is below 50%
			// If it is, we search for a refuge
			if(me().getWater() < 0.5*this.maxWater){
				log("Finding a refuge");
				path = search.breadthFirstSearchAvoidingBlockedRoads(
					currentPosition,
					transitionsSet,
					refuges
				);
				changeState(State.MOVING_TO_REFUGE);

				if (path == null) {
					path = randomWalk();
					log("Trying to move to refugee, but couldn't find path");
				}
				target = path.get(path.size() - 1);
				sendMove(time, path);
				return;
			}
			else{
				log("Finding a hydrant");
				path = search.breadthFirstSearchAvoidingBlockedRoads(
						currentPosition,
						transitionsSet,
						getHydrants()
					);
				changeState(State.MOVING_TO_HYDRANT);

				if (path == null) {
					path = randomWalk();
					log("Trying to move to hydrant, but couldn't find path");
				}
				target = path.get(path.size() - 1);
				sendMove(time, path);
				return;
			}
		}
		
		if(path != null && !path.isEmpty() && currentPosition.getValue() != path.get(path.size() - 1).getValue()){
			EntityID pathTarget = path.get(path.size() - 1);
			
			path = search.breadthFirstSearchAvoidingBlockedRoads(currentPosition, transitionsSet, pathTarget);
			if (path == null) {
				path = randomWalk();
				sendMove(time, path);
				return;
			}
			changeState(State.RESUMING_RANDOM_WALKING);
			sendMove(time, path);
			return;
		}
		else{
			path = randomWalk();
			sendMove(time, path);
			return;
		}
	}

	private Set<Pair<EntityID, EntityID>> getTransitionsSet() {
		String ss = "";
		Set<Pair<EntityID, EntityID>> transitionsSet =
				new HashSet<Pair<EntityID, EntityID>>();
		for (Pair<EntityID, EntityID> transition : transitionsBlocked) {
			if (transition != null) {
				transitionsSet.add(transition);
				ss += transition.first() + "->" + transition.second() + ", ";
			}
		}
		log("transitionsBlocked: " + ss);
		return transitionsSet;
	}
	
	private void sendMessageAboutPerceptions(ChangeSet changed, boolean complete) {
		// Send a message about all the perceptions
		Message msg;
		if (complete){
			msg = composeMessageComplete(changed);
		}
		else{
			msg = composeMessageIncomplete(changed);
		}

		if (this.channelComm) {
			if (!msg.getParameters().isEmpty() && !channelList.isEmpty()) {
				for (Pair<Integer,Integer> channel : channelList) {
					sendSpeak(currentTime, channel.first(),
							msg.getMessage(channel.second().intValue()));
				}
			}
		}
	}
	
	
	protected List<EntityID> getHydrants(){
		List<EntityID> result = new ArrayList<EntityID>();
		Collection<StandardEntity> b = model.
				getEntitiesOfType(StandardEntityURN.HYDRANT); // List of hydrants
		
		for(StandardEntity next : b){
			if(next instanceof Hydrant){
				result.add(next.getID());;
			}
		}
		
		return result;
	}

	@Override
	protected boolean amIBlocked(int time) {
		return math.geom2d.Point2D.distance(lastX, lastY, currentX, currentY) < MIN_WALK_LENGTH
					&& isMovingState()
					&& time > 3;
	}

	@Override
	protected void refreshTaskTable(ChangeSet changed) {
		Set<EntityID> fires = new HashSet<EntityID>();
		burntBuildings = new HashSet<EntityID>();

		for (StandardEntity next : model.getEntitiesOfType(StandardEntityURN.BUILDING)) {
			Building b = (Building) next;
			if (b.isOnFire())
				fires.add(next.getID());
			if (b.isFierynessDefined()
					&& b.getFierynessEnum()
					.equals(StandardEntityConstants.Fieryness.BURNT_OUT))
				burntBuildings.add(next.getID());
		}

		taskTable.keySet().retainAll(fires);

		for (EntityID next : fires) {
			if (!taskTable.containsKey(next)) {
				taskTable.put(next, new HashSet<EntityID>());
			}
		}
	}
	
	@Override
	protected EntityID selectTask() {
		EntityID result = null;
		
		List<EntityID> onFireList = new ArrayList<EntityID>(taskTable.keySet());
		
		
		// Get the closest - and less burned - building
		if(!onFireList.isEmpty()){
			Collections.sort(onFireList, DISTANCE_COMPARATOR);
			
			result = onFireList.get(0);
		}
		boolean found = false;
		search:
		for (int i=0;i < 0.6*onFireList.size();i++){
			Building building = (Building) model.getEntity(onFireList.get(i));
			if(building.isFierynessDefined()){
				if (building.getFierynessEnum().equals(StandardEntityConstants.Fieryness.HEATING)){
					result = onFireList.get(i);
					found = true;
					break search;
				}
				if (building.getFierynessEnum().equals(StandardEntityConstants.Fieryness.BURNING)){
					result = onFireList.get(i);
					found = true;
					break search;
				}
			}
		}
		
		if (!found){
		newsearch:
			for (int j=(int) (0.6*onFireList.size());j >0 ;j--){
				Building building = (Building) model.getEntity(onFireList.get(j));
				if(building.isFierynessDefined()){
					if (!building.getFierynessEnum().equals(StandardEntityConstants.Fieryness.BURNT_OUT)){
						result = onFireList.get(j);
						break newsearch;
						}
				}	
			}	
		}	
			
		log("Closest building:" + result);

		return result;
	}
	
	private void refreshMyTasks(EntityID target){
		for (Set<EntityID> agents : taskTable.values()) {
			if (agents != null) {
				agents.remove(me().getID());
			}
		}

		taskTable.get(target).add(me().getID());
	}
	
	@Override
	protected void dropTask(int time, ChangeSet changed) {
		if (!taskTable.containsKey(target) || numberOfCyclesDistantFromFire > 10) {
			taskDropped = target;
			target = null;
			numberOfCyclesDistantFromFire = 0;
		}
	}

	private boolean isMovingState() {
		List<State> ss = new ArrayList<State>();
		ss.add(State.MOVING_TO_FIRE);
		ss.add(State.MOVING_TO_REFUGE);
		ss.add(State.RANDOM_WALKING);
		ss.add(State.RESUMING_RANDOM_WALKING);
		ss.add(State.TAKING_ALTERNATE_ROUTE);
		ss.add(State.RETURNING_TO_SECTOR);

		return ss.contains(state);
	}
	
	private void changeState(State state) {
		this.state = state;
		log("Changed state to: " + this.state);
	}	
	
	// Distance comparator
	private class DistanceComparator implements Comparator<EntityID>{
		private EntityID agent;
		
		public DistanceComparator(EntityID agent){
			this.agent = agent;
		}
		
		@Override
		public int compare(EntityID o1, EntityID o2) {
			int distance1 = model.getDistance(agent, o1);
			int distance2 = model.getDistance(agent, o2);
			
			if(distance1 < distance2)	return -1;
			if(distance1 > distance2)	return 1;
			return 0;
		}
		
	}
	
	// Return a cluster of fire => All the buildings close to a building on fire
	private LinkedList<EntityID> getFireCluster(EntityID onFireBuilding){
		LinkedList<EntityID> cluster = new LinkedList<EntityID>();
		Set<EntityID> onFire = new HashSet<EntityID>(taskTable.keySet());
		onFire.addAll(burntBuildings);
		//for(EntityID bb : onFire)
		//	log("Building which is candidate for cluster: " + model.getEntity(bb));
		
		cluster.addLast(onFireBuilding);
		onFire.remove(onFireBuilding);
		
		for(int i = 0; i < cluster.size(); i++) {
			EntityID clusterBuilding = cluster.get(i);
			for(EntityID otherBuilding : onFire){
				if(model.getDistance(clusterBuilding, otherBuilding) < dangerousDistance){
					cluster.addLast(otherBuilding);
				}
			}
			onFire.removeAll(cluster);
		}
		
		return cluster;
	}
	
	private List<EntityID> getConvexHull(LinkedList<EntityID> cluster){
		List<BuildingPoint> points = new ArrayList<BuildingPoint>();
		
		for(EntityID buildingID : cluster){
			Building building = (Building) model.getEntity(buildingID);
			
			int[] apexes = building.getApexList();
			for(int i = 0; i < apexes.length; i = i + 2){
				points.add(new BuildingPoint(apexes[i], apexes[i+1], buildingID));
			}
		}
		
		Point2D[] pointArray = new Point2D[points.size()];
		int i = 0;
		for(BuildingPoint point : points){
			pointArray[i] = point;
			i++;
		}
		
		GrahamScan convexHull = new GrahamScan(pointArray);
		List<EntityID> convexList = new ArrayList<EntityID>(convexHull.getBuildings());
		
		// Collections.sort(convexList, DISTANCE_COMPARATOR);
		
		return convexList;
	}
	
	protected List<EntityID> randomWalk() {
		List<EntityID> result = new ArrayList<EntityID>();
		EntityID current = currentPosition;
		
		if (!sector.getLocations().keySet().contains(currentPosition)) {
			List<EntityID> local = new ArrayList<EntityID>(sector
					.getLocations().keySet());
			changeState(State.RETURNING_TO_SECTOR);
			List<EntityID> aux_path = search.breadthFirstSearchAvoidingBlockedRoads(currentPosition, getTransitionsSet(), local);
			if (aux_path == null)
				aux_path = super.randomWalk();
			return aux_path;
		}

		for (int i = 0; i < RANDOM_WALK_LENGTH; ++i) {
			result.add(current);
			List<EntityID> possible = new ArrayList<EntityID>();

			for (EntityID next : sector.getNeighbours(current))
				if (model.getEntity(next) instanceof Road)
					possible.add(next);

			Collections.shuffle(possible, new Random(me().getID().getValue()
					+ currentTime));
			boolean found = false;

			for (EntityID next : possible) {
				if (!result.contains(next)) {
					current = next;
					found = true;
					break;
				}
			}
			if (!found)
				break; // We reached a dead-end.
		}

		result.remove(0); // Remove actual position from path
		changeState(State.RANDOM_WALKING);
		return result;
	}
	
	private Message composeMessageIncomplete(ChangeSet changed) {
		return composeMessageIncomplete(changed, new Message());
	}
	
	/** Compõe uma mensagem para ser enviada de acordo com o que o agente vê */
	private Message composeMessageIncomplete(ChangeSet changed, Message message) {

		// Pedir socorro se está bloqueado ou buried
		if (((Human)me()).getBuriedness() != 0
				|| (amIBlocked(currentTime) && (currentTime - lastTimeNotBlocked >= 5))){
			message = addRescueMyselfMessage(message);
			
		}else if (hasRequestedToBeSaved){
			message = addGotRescuedMessage(message);
		}
		
		for (EntityID buildingID : getVisibleEntitiesOfType(
				StandardEntityURN.BUILDING, changed)) {
			Building building = (Building) model.getEntity(buildingID);

			/*
			 * Se o prédio está em chamas e este incêndio não é conhecido, deve
			 * se enviar uma mensagem falando sobre ele
			 */
			if (building.isOnFire() && !buildingsOnFire.contains(buildingID)) {
				// O incêndio passa a ser conhecido
				buildingsOnFire.add(buildingID);

			}
			/*
			 * Se o prédio não está em chamas, mas havia um incêndio nele
			 * anteriormente, há duas opções: 1) O incêndio comsumiu todo o
			 * prédio; 2) O incêndio foi extinguido
			 */
			else if (!building.isOnFire()
					&& buildingsOnFire.contains(buildingID)) {
				// Não se sabe mais de nenhum incêndio neste prédio
				buildingsOnFire.remove(buildingID);
			}
		}

		/*
		 * Se algum bloqueio visível não é conhecido, deve-se enviar uma
		 * mensagem relatando-o
		 */
		for (EntityID blockadeID : getVisibleEntitiesOfType(
				StandardEntityURN.BLOCKADE, changed)) {
			Blockade blockade = (Blockade) model.getEntity(blockadeID);

			if (!knownBlockades.contains(blockadeID)) {
				lti.message.type.Blockade block = new lti.message.type.Blockade(
						blockadeID.getValue(), blockade.getPosition()
								.getValue(), blockade.getX(), blockade.getY(),
						blockade.getRepairCost());
				message.addParameter(block);
				knownBlockades.add(blockadeID);
			}
		}

		Set<EntityID> toRemove = new HashSet<EntityID>();

		/*
		 * Se algum bloqueio conhecido não se encontra mais em seu lugar, ele
		 * foi removido
		 */
		for (EntityID blockadeID : knownBlockades) {
			if (model.getEntity(blockadeID) == null) {
				/*BlockadeCleared cleared = new BlockadeCleared(
						blockadeID.getValue());
				message.addParameter(cleared);*/
				toRemove.add(blockadeID);
			}
		}

		knownBlockades.removeAll(toRemove);

		Set<EntityID> victims = new HashSet<EntityID>();
		StandardEntityURN[] urns = { StandardEntityURN.AMBULANCE_TEAM,
				StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.CIVILIAN,
				StandardEntityURN.POLICE_FORCE };
		/*
		 * Victims: - AT, FB and PF who are alive and buried; - Civilians who
		 * are alive and are either buried or inside a building that is not a
		 * refuge.
		 */
		for (int i = 0; i < 4; i++) {
			Set<EntityID> nonRefugeBuildings = new HashSet<EntityID>(
					buildingIDs);
			nonRefugeBuildings.removeAll(getRefuges());

			for (EntityID next : getVisibleEntitiesOfType(urns[i], changed)) {
				Human human = (Human) model.getEntity(next);
				if (human.isHPDefined() && human.getHP() != 0) {
					if (urns[i].equals(StandardEntityURN.CIVILIAN)
							&& nonRefugeBuildings.contains(human.getPosition())) {
						victims.add(next);

					} else if (human.isBuriednessDefined()
							&& human.getBuriedness() != 0
							&& nonRefugeBuildings.contains(human.getPosition())) {
						victims.add(next);
					}
				}
			}
		}

		/*
		 * Se alguma vítima visível não é conhecida, deve-se enviar uma mensagem
		 * relatando-a
		 */
		for (EntityID next : victims) {
			StandardEntity entity;
			Human human;
			int urn;

			if (!knownVictims.contains(next)) {
				entity = model.getEntity(next);

				if (entity != null && entity instanceof Human) {
					switch (entity.getStandardURN()) {
					case AMBULANCE_TEAM:
						human = (AmbulanceTeam) entity;
						urn = 0;
						break;

					case FIRE_BRIGADE:
						human = (FireBrigade) entity;
						urn = 1;
						break;

					case POLICE_FORCE:
						human = (PoliceForce) entity;
						urn = 2;
						break;

					default:
						human = (Civilian) entity;
						urn = 3;
					}

					Victim victim = new Victim(next.getValue(), human
							.getPosition().getValue(), human.getHP(),
							human.getDamage(), human.getBuriedness(), urn);
					message.addParameter(victim);
					knownVictims.add(next);
				}
			}
		}

		toRemove = new HashSet<EntityID>();

		/*
		 * Se alguma vítima conhecida não está no lugar aonde deveria estar ou
		 * morreu, deve-se enviar uma mensagem relatando a situação
		 */
		for (EntityID victim : knownVictims) {
			StandardEntity entity = model.getEntity(victim);

			if (entity != null && entity instanceof Human) {
				Human human = (Human) entity;

				if (human.isHPDefined() && human.getHP() == 0) {
					VictimDied death = new VictimDied(victim.getValue());
					message.addParameter(death);
					toRemove.add(victim);
				} else if (human.isBuriednessDefined() &&
						human.getBuriedness() == 0 && human instanceof Civilian) {
					VictimRescued rescue = new VictimRescued(victim.getValue());
					message.addParameter(rescue);
					toRemove.add(victim);
				}
			}
		}

		knownVictims.removeAll(toRemove);

		/* Se uma tarefa foi abandonada, envia-se uma mensagem */
		if (taskDropped != null) {
			TaskDrop drop = new TaskDrop(taskDropped.getValue());
			message.addParameter(drop);

			/* Se uma tarefa foi escohida, envia-se uma mensagem relatando-a */
			if (target != null) {
				TaskPickup task = new TaskPickup(target.getValue());
				message.addParameter(task);
			}
		}
		/* Se uma tarefa foi escohida, envia-se uma mensagem relatando-a */
		if (target != null && currentTime == config.getIntValue(KernelConstants.IGNORE_AGENT_COMMANDS_KEY)) {
			TaskPickup task = new TaskPickup(target.getValue());
			message.addParameter(task);
		}
		return message;
	}
}


