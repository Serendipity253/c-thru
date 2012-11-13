//
// Flow Log Controller.
//

define([], function () {

	return Em.ArrayProxy.create({
		content: [],
		types: Em.Object.create(),
		get_flowlet: function (id) {
			id = id + "";
			var content = this.types.Flowlet.content;
			for (var k = 0; k < content.length; k++) {
				if (content[k].name === id) {
					return content[k];
				}
			}
			content = this.types.Stream.content;
			for (k = 0; k < content.length; k++) {
				if (content[k].name === id) {
					return content[k];
				}
			}
		},

		__currentFlowletLabel: 'processed.count',

		load: function (app, id) {

			var self = this;

			C.interstitial.loading();
			C.get('manager', {
				method: 'getFlowDefinition',
				params: [app, id]
			}, function (error, response) {

				if (!response.params) {
					return;
				}

				response.params.applicationId = app;

				self.set('current', C.Mdl.Flow.create(response.params));

				var flowlets = response.params.flowlets;
				var objects = [];
				for (var i = 0; i < flowlets.length; i ++) {
					objects.push(C.Mdl.Flowlet.create(flowlets[i]));
				}
				self.set('types.Flowlet', Em.ArrayProxy.create({content: objects}));
				
				var streams = response.params.flowStreams;
				objects = [];
				for (var i = 0; i < streams.length; i ++) {
					objects.push(C.Mdl.Stream.create(streams[i]));
				}
				self.set('types.Stream', Em.ArrayProxy.create({content: objects}));

				// READY TO GO

			});

			function logInterval () {

				if (C.router.currentState.get('path') !== 'root.flows.log') {
					clearInterval(self.interval);
					return;
				}

				C.get('monitor', {
					method: 'getLog',
					params: [app, id, 1024 * 10]
				}, function (error, response) {

					if (C.router.currentState.get('path') !== 'root.flows.log') {
						clearInterval(self.interval);
						return;
					}

					if (error) {

						C.router.applicationController.view.set('responseBody', JSON.stringify(error));

					} else {

						var items = response.params;
						C.router.applicationController.view.set('responseBody', items.join('\n'));
					}
					
					var textarea = C.router.applicationController.view.get('logView').get('element');

					setTimeout(function () {
						textarea = $(textarea);
						textarea.scrollTop(textarea[0].scrollHeight - textarea.height());
					}, 200);

					C.interstitial.hide();

				});
			}

			logInterval();

			this.interval = setInterval(logInterval, 1000);

		},

		interval: null,
		unload: function () {

			this.get('content').clear();
			this.set('content', []);
			this.set('types.Flowlet', Em.Object.create());
			this.set('current', null);
			clearInterval(this.interval);

		}

	});
});