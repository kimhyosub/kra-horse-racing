package com.sub.kra.collect;

import com.sub.kra.config.KraProperties;
import com.sub.kra.store.CsvExporter;
import com.sub.kra.store.RawStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 실행 진입점: kra.mode = probe | collect | export | all
 * 종료 코드: 0 정상, 2 호출 한도·키 문제로 중단, 3 일부 실패 (자동 실행 스크립트가 이 값으로 성공 여부를 판단)
 */
@Component
public class CollectRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CollectRunner.class);
    private final KraProperties props;
    private final CollectService collect;
    private final CsvExporter exporter;
    private final RawStore store;
    private final ProbeService probe;
    private int exitCode = 0;

    public CollectRunner(KraProperties props, CollectService collect, CsvExporter exporter, RawStore store, ProbeService probe) {
        this.props = props; this.collect = collect; this.exporter = exporter; this.store = store; this.probe = probe;
    }

    @Override
    public void run(String... args) throws Exception {
        String mode = props.getMode().toLowerCase();
        if (mode.equals("probe")) { probe.run(); return; }
        if (mode.equals("collect") || mode.equals("all")) {
            CollectService.Summary s = collect.run();
            exitCode = s.stoppedByLimit ? 2 : (s.failed > 0 ? 3 : 0);
        }
        if (mode.equals("export") || mode.equals("all")) {
            for (String api : props.getApis().keySet()) {
                if (!props.getExportApis().isEmpty() && !props.getExportApis().contains(api)) continue;
                if (new File(store.getRoot(), api).isDirectory())
                    exporter.export(store.getRoot(), api, new File(props.getOutputDir()));
                else log.info("[{}] 저장된 원본 없음 → CSV 생략", api);
            }
        }
    }

    @Override
    public int getExitCode() { return exitCode; }
}
