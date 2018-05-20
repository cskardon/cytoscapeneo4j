package nl.corwur.cytoscape.neo4j.internal.tasks;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.cytoscape.model.CyNode;
import org.cytoscape.task.AbstractNodeViewTask;
import org.cytoscape.view.layout.CyLayoutAlgorithm;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.View;
import org.cytoscape.work.Task;
import org.cytoscape.work.TaskMonitor;

import nl.corwur.cytoscape.neo4j.internal.Services;
import nl.corwur.cytoscape.neo4j.internal.tasks.importgraph.DefaultImportStrategy;
import nl.corwur.cytoscape.neo4j.internal.tasks.importgraph.ImportGraphStrategy;
import nl.corwur.cytoscape.neo4j.internal.tasks.importgraph.ImportGraphToCytoscape;
import nl.corwur.cytoscape.neo4j.internal.graph.Graph;
import nl.corwur.cytoscape.neo4j.internal.neo4j.CypherQuery;
import nl.corwur.cytoscape.neo4j.internal.neo4j.Neo4jClientException;

public class ExpandNodeTask extends AbstractNodeViewTask implements Task, ActionListener {

	private final transient Services services;
    private final ImportGraphStrategy importGraphStrategy;
	private Boolean redoLayout;
	private String edge;


	public ExpandNodeTask(View<CyNode> nodeView, CyNetworkView networkView, Services services, Boolean redoLayout, String edge) {
		super(nodeView, networkView);
		this.services = services;
		this.importGraphStrategy = new DefaultImportStrategy();
		this.redoLayout = redoLayout;
		this.edge = edge;
	}
	
	private void expand() throws InterruptedException, ExecutionException {
		CyNode cyNode = (CyNode)this.nodeView.getModel();
		
		Long refid = this.netView.getModel().getRow(cyNode).get(this.importGraphStrategy.getRefIDName(), Long.class);
		String query;
		if (this.edge == null) {
			query = "match p=(n)-[r]-() where ID(n) = " + refid +" return p"; 
		}
		else {
			query = "match p=(n)-["+this.edge+"]-() where ID(n) = " + refid +" return p";
		}
		CypherQuery cypherQuery = CypherQuery.builder().query(query).build();
		
        CompletableFuture<Graph> result = CompletableFuture.supplyAsync(() -> getGraph(cypherQuery));

        while(!result.isDone()) {
            if(this.cancelled) {
                result.cancel(true);
            }
            Thread.sleep(400);
        }
        if(result.isCompletedExceptionally()) {
            throw new IllegalStateException("Error executing cypher query");
        }

        Graph graph = result.get();

        ImportGraphToCytoscape cypherParser = new ImportGraphToCytoscape(this.netView.getModel(), importGraphStrategy, () -> this.cancelled);
        
        cypherParser.importGraph(graph);
        if (this.redoLayout) {
	        CyLayoutAlgorithm layout = services.getCyLayoutAlgorithmManager().getDefaultLayout();
	        Set<View<CyNode>> nodes = new HashSet<>();
	        insertTasksAfterCurrentTask(layout.createTaskIterator(this.netView, layout.createLayoutContext(), nodes, null));
        }
        services.getVisualMappingManager().getVisualStyle(this.netView).apply(this.netView);
        this.netView.updateView();
		
	}

	@Override
	public void run(TaskMonitor taskMonitor) throws Exception {
	
		taskMonitor.setTitle("Expanding node");
		this.expand();
        
	}

	private Graph getGraph(CypherQuery query) {
        try {
            return services.getNeo4jClient().getGraph(query);
        } catch (Neo4jClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

	@Override
	public void actionPerformed(ActionEvent e) {
		try {
			this.expand();
		} catch (Exception exception) {
			
		}
	}

}
