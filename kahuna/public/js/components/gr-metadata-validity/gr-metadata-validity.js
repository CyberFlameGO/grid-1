import angular from 'angular';

import template from './gr-metadata-validity.html';
import './gr-metadata-validity.css';

export const module = angular.module('gr.metadataValidity', []);

module.controller('grMetadataValidityCtrl', [ '$rootScope', function ($rootScope) {
    let ctrl = this;

    function updateState() {
        ctrl.image.get().then(image => {
            ctrl.isDeleted = image.data.softDeletedMetadata !== undefined;
            ctrl.showInvalidReasons = Object.keys(image.data.invalidReasons).length !== 0 || ctrl.isDeleted;
            ctrl.invalidReasons = image.data.invalidReasons;
            ctrl.isOverridden = ctrl.showInvalidReasons && image.data.valid;
            ctrl.isStrongWarning = ctrl.isDeleted || !ctrl.isOverridden || image.data.cost === "pay";
        });
    }

    $rootScope.$on('leases-updated', () => {
        updateState();
    });

    updateState();
}]);

module.directive('grMetadataValidity', [function () {

    return {
        restrict: 'E',
        controller: 'grMetadataValidityCtrl',
        controllerAs: 'ctrl',
        bindToController: true,
        transclude: true,
        template: template,
        scope: {
            image: '=grImage'
        }
    };
}]);
