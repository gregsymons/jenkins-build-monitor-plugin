/*
 * The MIT License
 *
 * Copyright (c) 2013, Jan Molak, SmartCode Ltd http://smartcodeltd.co.uk
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.smartcodeltd.jenkinsci.plugins.buildmonitor;

import com.smartcodeltd.jenkinsci.plugins.buildmonitor.viewmodel.JobView;
import com.smartcodeltd.jenkinsci.plugins.buildmonitor.viewmodel.plugins.BuildAugmentor;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Descriptor.FormException;
import hudson.model.Job;
import hudson.model.ListView;
import hudson.model.Run;
import hudson.model.ViewDescriptor;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.codehaus.jackson.map.ObjectMapper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static hudson.Util.filter;

/**
 * @author Jan Molak
 */
public class BuildMonitorView extends ListView {

    /**
     * @param name  Name of the view
     */
    @DataBoundConstructor
    public BuildMonitorView(String name) {
        super(name);
    }

    @Extension
    public static final class Descriptor extends ViewDescriptor {
        public Descriptor() {
            super(BuildMonitorView.class);
        }

        @Override
        public String getDisplayName() {
            return "Build Monitor View";
        }

        /**
         * Cut-n-paste from ListView$Descriptor as we cannot inherit from that class
         */
        public FormValidation doCheckIncludeRegex(@QueryParameter String value) {
            String v = Util.fixEmpty(value);
            if (v != null) {
                try {
                    Pattern.compile(v);
                } catch (PatternSyntaxException pse) {
                    return FormValidation.error(pse.getMessage());
                }
            }
            return FormValidation.ok();
        }
    }

    @Override
    protected void submit(StaplerRequest req) throws ServletException, IOException, FormException {
        super.submit(req);

        String requestedOrdering = req.getParameter("order");

        try {
            currentConfig().setOrder(orderIn(requestedOrdering));
        } catch (Exception e) {
            throw new FormException("Can't order projects by " + requestedOrdering, "order");
        }
    }

    /**
     * Because of how org.kohsuke.stapler.HttpResponseRenderer is implemented
     * it can only work with net.sf.JSONObject in order to produce correct application/json output
     *
     * @return
     * @throws Exception
     */
    @JavaScriptMethod
    public JSONObject fetchJobViews() throws Exception {
        return jsonFrom(jobViews());
    }

    public boolean isEmpty() {
        return jobViews().isEmpty();
    }

    // used in the configure-entries.jelly form
    public String currentOrder() {
        return currentConfig().getOrder().getClass().getSimpleName();
    }

    // --

    private JSONObject jsonFrom(List<JobView> jobViews) throws IOException {
        ObjectMapper m = new ObjectMapper();

        return (JSONObject) JSONSerializer.toJSON("{jobs:" + m.writeValueAsString(jobViews) + "}");
    }

    private List<JobView> jobViews() {
        //A little bit of evil to make the type system happy.
        @SuppressWarnings("unchecked")
        List<Job<?, ?>> projects = new ArrayList(filter(super.getItems(), Job.class));
        List<JobView> jobs = new ArrayList<JobView>();

        Collections.sort(projects, currentConfig().getOrder());

        for (Job project : projects) {
            jobs.add(JobView.of(project, currentConfig(), withAugmentationsIfTheyArePresent()));
        }

        return jobs;
    }

    private BuildAugmentor withAugmentationsIfTheyArePresent() {
        return BuildAugmentor.fromDetectedPlugins();
    }

    /**
     * When Jenkins is started up, Jenkins::loadTasks is called.
     * At that point config.xml file is unmarshaled into a Jenkins object containing a list of Views, including BuildMonitorView objects.
     *
     * The unmarshaling process sets private fields on BuildMonitorView objects directly, ignoring their constructors.
     * This means that if there's a private field added to this class (say "config"), the previously persisted versions of this class can no longer
     * be correctly un-marshaled into the new version as they don't define the new field and the object ends up in an inconsistent state.
     *
     * @return the previously persisted version of the config object, default config, or the deprecated "order" object, converted to a "config" object.
     */
    private Config currentConfig() {
        if (creatingAFreshView()) {
            config = Config.defaultConfig();
        }
        else if (deserailisingFromAnOlderFormat()) {
            migrateFromOldToNewConfigFormat();
        }

        return config;
    }

    private boolean creatingAFreshView() {
        return config == null && order == null;
    }

    // Is config.xml saved in a format prior to version 1.6+build.150 of Build Monitor?
    private boolean deserailisingFromAnOlderFormat() {
        return config == null && order != null;
    }

    // If an older version of config.xml is loaded, "config" field is missing, but "order" is present
    private void migrateFromOldToNewConfigFormat() {
        Config c = new Config();
        c.setOrder(order);

        config = c;
        order = null;
    }

    @SuppressWarnings("unchecked")
    private Comparator<Job<?, ?>> orderIn(String requestedOrdering) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        String packageName = this.getClass().getPackage().getName() + ".order.";

        return (Comparator<Job<?, ?>>) Class.forName(packageName + requestedOrdering).newInstance();
    }

    private Config config;

    @Deprecated // use Config instead
    private Comparator<Job<?, ?>> order;      // note: this field can be removed when people stop using versions prior to 1.6+build.150
}