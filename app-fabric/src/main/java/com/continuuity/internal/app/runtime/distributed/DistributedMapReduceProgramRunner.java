/*
 * Copyright 2012-2014 Continuuity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.internal.app.runtime.distributed;

import com.continuuity.api.mapreduce.MapReduceSpecification;
import com.continuuity.app.ApplicationSpecification;
import com.continuuity.app.program.Program;
import com.continuuity.app.runtime.ProgramController;
import com.continuuity.app.runtime.ProgramOptions;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.proto.ProgramType;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Runs Mapreduce programm in distributed environment
 */
public final class DistributedMapReduceProgramRunner extends AbstractDistributedProgramRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DistributedMapReduceProgramRunner.class);

  @Inject
  public DistributedMapReduceProgramRunner(TwillRunner twillRunner, Configuration hConf, CConfiguration cConf) {
    super(twillRunner, hConf, cConf);
  }

  @Override
  protected ProgramController launch(Program program, ProgramOptions options,
                                     File hConfFile, File cConfFile, ApplicationLauncher launcher) {
    // Extract and verify parameters
    ApplicationSpecification appSpec = program.getSpecification();
    Preconditions.checkNotNull(appSpec, "Missing application specification.");

    ProgramType processorType = program.getType();
    Preconditions.checkNotNull(processorType, "Missing processor type.");
    Preconditions.checkArgument(processorType == ProgramType.MAPREDUCE, "Only MAPREDUCE process type is supported.");

    MapReduceSpecification spec = appSpec.getMapReduce().get(program.getName());
    Preconditions.checkNotNull(spec, "Missing MapReduceSpecification for %s", program.getName());

    LOG.info("Launching MapReduce program: " + program.getName() + ":" + spec.getName());
    TwillController controller = launcher.launch(new MapReduceTwillApplication(program, spec,
                                                                               hConfFile, cConfFile, eventHandler));

    return new MapReduceTwillProgramController(program.getName(), controller).startListen();
  }
}
